"use strict";

// 세션 쿠키는 브라우저가 관리합니다. 비밀번호·토큰은 브라우저 저장소에 보관하지 않습니다.
const $ = (selector) => document.querySelector(selector);
const state = {
    employee: null, view: "mine", page: 0, detail: null,
    listRequest: 0, detailRequest: 0, employeeTokenRequest: 0,
    previewUrl: null, uploadKey: null, requests: new Set(),
    originalImageUrl: null, originalImageRequest: null
};
const roleNames = { EMPLOYEE: "직원", REVIEWER: "검수자", ADMIN: "관리자" };
const statusNames = {
    AUTO_APPROVED: "자동 승인", NEEDS_REVIEW: "검수 필요", NEEDS_RECAPTURE: "재촬영 필요",
    MANUAL_ENTRY: "직접 입력 필요", UNREADABLE: "판독 불가", APPROVED: "승인 완료", REJECTED: "반려"
};
const jobNames = { QUEUED: "추출 대기", PROCESSING: "추출 중", RETRY_WAIT: "재시도 대기", COMPLETED: "추출 처리 완료", FAILED: "추출 실패" };
const pendingJobs = new Set(["QUEUED", "PROCESSING", "RETRY_WAIT"]);
const editableStatuses = new Set(["NEEDS_REVIEW", "NEEDS_RECAPTURE", "MANUAL_ENTRY", "UNREADABLE"]);
const correctionKeys = ["shopName", "date", "totalAmount", "paymentMethod", "businessRegistrationNumber"];
const money = (value) => value == null ? "—" : `${Number(value).toLocaleString("ko-KR")}원`;
const dateTime = (value) => value ? new Date(value).toLocaleString("ko-KR", { timeZone: "Asia/Seoul", hour12: false }) : "—";

function node(tag, text, className) {
    const element = document.createElement(tag);
    if (text != null) element.textContent = text;
    if (className) element.className = className;
    return element;
}

function notify(message, error = false) {
    const target = $("#receipt-dialog").open ? $("#detail-notice") : $("#notice");
    target.textContent = message;
    target.classList.toggle("error", error);
    target.hidden = false;
}

async function readResponse(response) {
    const text = await response.text();
    if (!text) return null;
    try { return JSON.parse(text); } catch { return null; }
}

async function api(path, { method = "GET", body, headers = {}, responseType = "json",
    controller = new AbortController() } = {}) {
    state.requests.add(controller);
    const options = { method, credentials: "same-origin", signal: controller.signal, headers: { ...headers } };
    try {
        // 로그인 시 CSRF 토큰이 바뀌므로, 변경 요청 직전에 현재 세션의 토큰을 받습니다.
        if (method !== "GET") {
            const csrfResponse = await fetch("/api/auth/csrf", { credentials: "same-origin", signal: controller.signal, cache: "no-store" });
            if (!csrfResponse.ok) throw new Error("인증 정보를 가져오지 못했습니다. 새로고침 후 다시 시도하세요.");
            const csrf = await csrfResponse.json();
            options.headers[csrf.headerName] = csrf.token;
        }
        if (body instanceof FormData) options.body = body;
        else if (body !== undefined) {
            options.headers["Content-Type"] = "application/json";
            options.body = JSON.stringify(body);
        }
        const response = await fetch(path, { ...options, cache: "no-store" });
        if (!response.ok) {
            const data = await readResponse(response);
            const messages = {
                400: path === "/api/auth/password" ? "설정 토큰과 비밀번호를 확인하세요. 토큰이 만료되었거나 이미 사용되었을 수 있습니다." : "입력값을 확인하세요.",
                401: path === "/api/auth/login" ? "로그인 ID와 비밀번호를 확인하세요." : "세션이 만료되었습니다. 다시 로그인하세요.",
                403: "권한이 없거나 인증 정보가 변경되었습니다. 다시 조회하거나 로그인하세요.",
                404: path.endsWith("/image") ? "원본 이미지를 찾을 수 없거나 접근할 수 없습니다." : "해당 자료가 없거나 접근할 수 없습니다.",
                409: "데이터가 변경되었거나 중복된 요청입니다. 최신 내용을 다시 조회하세요.",
                413: "이미지 크기는 10MB 이하여야 합니다.",
                502: "자료를 가져오지 못했습니다. 잠시 후 다시 시도하세요."
            };
            if (response.status === 401 && state.employee) endSession();
            const error = new Error(data?.detail || messages[response.status] || `요청에 실패했습니다. (HTTP ${response.status})`);
            error.status = response.status;
            throw error;
        }
        return responseType === "blob" ? await response.blob() : await readResponse(response);
    } catch (error) {
        if (error instanceof TypeError) throw new Error("서버에 연결할 수 없습니다. Spring Boot가 실행 중인지 확인하세요.");
        throw error;
    } finally {
        state.requests.delete(controller);
    }
}

async function run(button, action) {
    const originalText = button?.textContent;
    if (button) { button.disabled = true; button.textContent = "처리 중…"; }
    try { await action(); }
    catch (error) { if (error.name !== "AbortError") notify(error.message, true); }
    finally { if (button) { button.disabled = false; button.textContent = originalText; } }
}

function switchAuthTab(setup) {
    $("#login-form").hidden = setup;
    $("#setup-form").hidden = !setup;
    for (const [id, active] of [["#login-tab", !setup], ["#setup-tab", setup]]) {
        $(id).classList.toggle("active", active);
        $(id).setAttribute("aria-pressed", String(active));
    }
}

function clearIssuedToken() {
    state.employeeTokenRequest++;
    for (const prefix of ["issued", "reissued"]) {
        $(`#${prefix}-token`).value = "";
        $(`#${prefix}-result`).hidden = true;
        $(`#${prefix}-employee`).textContent = "";
        $(`#${prefix}-expires`).textContent = "";
    }
}

function showSetupToken(prefix, issued) {
    $(`#${prefix}-result`).hidden = false;
    $(`#${prefix}-employee`).textContent =
        `${issued.employee.name} · ${issued.employee.loginId} · 직원 번호 #${issued.employee.id}`;
    $(`#${prefix}-token`).value = issued.setupToken;
    $(`#${prefix}-expires`).textContent = `유효 기간: ${dateTime(issued.expiresAt)}까지`;
}

async function copySetupToken(selector) {
    const input = $(selector);
    if (!input.value) return;
    try {
        await navigator.clipboard.writeText(input.value);
        notify("설정 토큰을 복사했습니다.");
    } catch {
        input.select();
        notify("토큰이 선택되었습니다. Command+C 또는 Ctrl+C로 복사하세요.");
    }
}

function clearFile() {
    if (state.previewUrl) URL.revokeObjectURL(state.previewUrl);
    state.previewUrl = null;
    state.uploadKey = null;
    $("#upload-form").reset();
    $("#file-preview").removeAttribute("src");
    $("#file-preview").hidden = true;
    $("#file-name").textContent = "영수증 이미지 선택";
}

function endSession() {
    for (const request of state.requests) request.abort();
    state.employee = null;
    state.listRequest++;
    closeDetail();
    clearFile();
    clearIssuedToken();
    $("#receipt-rows").replaceChildren();
    for (const selector of ["#login-form", "#setup-form", "#issue-form", "#reissue-form", "#active-form"]) $(selector).reset();
    $("#workspace").hidden = true;
    $("#auth-screen").hidden = false;
    $("#breadcrumb").textContent = "시작하기";
    $("#employee-name").textContent = "";
    $("#employee-role").textContent = "";
    document.querySelectorAll("[data-view]").forEach((button) => {
        button.disabled = true;
        button.hidden = button.dataset.view !== "mine";
    });
    switchAuthTab(false);
}

async function enterWorkspace(employee) {
    state.employee = employee;
    $("#notice").hidden = true;
    $("#auth-screen").hidden = true;
    $("#workspace").hidden = false;
    $("#employee-name").textContent = employee.name;
    $("#employee-role").textContent = `${roleNames[employee.role]} · ${employee.loginId}`;
    $("#avatar").textContent = employee.name.slice(0, 1);
    document.querySelectorAll("[data-view]").forEach((button) => {
        button.disabled = false;
        button.hidden = (button.dataset.view === "review" && employee.role === "EMPLOYEE") ||
            (button.dataset.view === "employees" && employee.role !== "ADMIN");
    });
    await changeView("mine");
}

async function changeView(view) {
    closeDetail();
    state.listRequest++;
    clearIssuedToken();
    state.view = view;
    state.page = 0;
    $("#filter-form").reset();
    const titles = { mine: "내 영수증", review: "검수 대기", employees: "직원 계정" };
    $("#page-title").textContent = titles[view];
    $("#breadcrumb").textContent = titles[view];
    $("#page-description").textContent = view === "employees" ? "직원 계정을 발급하고 사용 상태를 관리하세요." :
        view === "review" ? "다른 직원이 제출한 영수증을 확인하고 검수하세요." : "제출한 영수증과 처리 결과를 확인하세요.";
    document.querySelectorAll("[data-view]").forEach((button) => button.classList.toggle("selected", button.dataset.view === view));
    $("#employees-view").hidden = view !== "employees";
    $("#receipts-view").hidden = view === "employees";
    $("#upload-panel").hidden = view !== "mine";
    $("#list-title").firstChild.textContent = view === "review" ? "검수할 영수증 " : "제출 내역 ";
    $("#list-description").textContent = view === "review" ? "본인 제출 건과 최종 처리된 영수증은 제외됩니다." : "최근 제출한 순서대로 표시됩니다.";
    if (view !== "employees") await loadReceipts();
}

function statusBadge(receipt) {
    const pending = pendingJobs.has(receipt.jobStatus);
    const label = pending ? jobNames[receipt.jobStatus] : statusNames[receipt.status] || jobNames[receipt.jobStatus] || "처리 대기";
    let style = "warning";
    if (pending) style = "processing";
    else if (["APPROVED", "AUTO_APPROVED"].includes(receipt.status)) style = "success";
    else if (["REJECTED", "UNREADABLE"].includes(receipt.status) || receipt.jobStatus === "FAILED") style = "error";
    return node("span", label, `badge ${style}`);
}

async function loadReceipts(background = false) {
    const requestId = ++state.listRequest;
    const filters = new FormData($("#filter-form"));
    if (filters.get("from") && filters.get("to") && filters.get("from") > filters.get("to")) {
        throw new Error("제출 종료일은 시작일 이후여야 합니다.");
    }
    const params = new URLSearchParams({ page: state.page, size: 10 });
    for (const [key, value] of filters) if (value) params.set(key, value);
    const endpoint = state.view === "review" ? "/api/receipts/review-queue" : "/api/receipts";
    $("#previous-page").disabled = true;
    $("#next-page").disabled = true;
    if (!background) $("#page-label").textContent = "목록을 불러오는 중…";
    let page;
    try { page = await api(`${endpoint}?${params}`); }
    catch (error) {
        if (requestId === state.listRequest) $("#page-label").textContent = "조회 실패 · 새로고침을 눌러주세요";
        throw error;
    }
    if (!state.employee || requestId !== state.listRequest) return;
    $("#receipt-count").textContent = page.totalElements.toLocaleString("ko-KR");
    $("#receipt-rows").replaceChildren();
    for (const receipt of page.content) {
        const row = node("tr");
        const title = node("td");
        title.append(node("strong", receipt.shopName || receipt.originalFileName || "처리 중인 영수증"), node("small", `#${receipt.id} · ${receipt.date || "사용일 미확인"}`));
        const status = node("td");
        status.append(statusBadge(receipt));
        const action = node("td");
        const button = node("button", "상세 →", "text-button");
        button.type = "button";
        button.setAttribute("aria-label", `영수증 ${receipt.id} 상세 보기`);
        button.addEventListener("click", () => run(button, () => openDetail(receipt.id)));
        action.append(button);
        row.append(title, node("td", money(receipt.totalAmount)), status, node("td", dateTime(receipt.createdAt)), action);
        $("#receipt-rows").append(row);
    }
    $("#list-empty").hidden = page.content.length > 0;
    $("#list-empty h3").textContent = state.view === "review" ? "검수할 영수증이 없어요" : "조회된 영수증이 없어요";
    $("#list-empty p").textContent = state.view === "review" ? "다른 직원의 제출 내역이나 검색 조건을 확인하세요." : "영수증을 제출하거나 검색 조건을 변경해 보세요.";
    $("#page-label").textContent = `${page.page + 1} / ${Math.max(page.totalPages, 1)} 페이지 · ${page.totalElements}건`;
    $("#previous-page").disabled = page.page === 0;
    $("#next-page").disabled = !page.hasNext;
    $("#list-pending-hint").hidden = !page.content.some((receipt) => pendingJobs.has(receipt.jobStatus));
}

function closeDetail() {
    state.detailRequest++;
    clearOriginalImage();
    state.detail = null;
    $("#receipt-dialog").close();
    $("#detail-json").textContent = "";
    $("#audit-events").replaceChildren();
    $("#rule-results").replaceChildren();
    $("#line-items").replaceChildren();
    $("#correction-form").reset();
    $("#decision-form").reset();
}

function clearOriginalImage() {
    state.originalImageRequest?.abort();
    state.originalImageRequest = null;
    if (state.originalImageUrl) URL.revokeObjectURL(state.originalImageUrl);
    state.originalImageUrl = null;
    const image = $("#original-image");
    image.onload = null;
    image.onerror = null;
    image.removeAttribute("src");
    image.hidden = true;
    $("#original-image-panel").hidden = true;
    $("#original-image-panel").setAttribute("aria-busy", "false");
    $("#original-image-status").textContent = "";
    $("#original-image-status").classList.remove("error");
    $("#show-original-image").textContent = "원본 보기";
    $("#show-original-image").setAttribute("aria-expanded", "false");
}

async function toggleOriginalImage() {
    if (!state.detail) return;
    if (!$("#original-image-panel").hidden) {
        clearOriginalImage();
        return;
    }

    const receipt = state.detail;
    const controller = new AbortController();
    state.originalImageRequest = controller;
    const panel = $("#original-image-panel");
    const status = $("#original-image-status");
    const image = $("#original-image");
    panel.hidden = false;
    panel.setAttribute("aria-busy", "true");
    status.textContent = "원본 이미지를 불러오고 있습니다…";
    $("#show-original-image").textContent = "원본 닫기";
    $("#show-original-image").setAttribute("aria-expanded", "true");

    // 상세 화면이 닫히거나 다른 영수증으로 바뀌면 늦게 도착한 이미지를 표시하지 않습니다.
    const isCurrent = () => state.employee && state.detail?.id === receipt.id &&
        state.originalImageRequest === controller;
    try {
        const blob = await api(`/api/receipts/${receipt.id}/image`, { responseType: "blob", controller });
        if (!isCurrent()) return;
        if (!["image/jpeg", "image/png"].includes(blob.type)) {
            throw new Error("원본 이미지를 표시할 수 없습니다. JPG 또는 PNG 파일인지 확인하세요.");
        }

        state.originalImageUrl = URL.createObjectURL(blob);
        image.alt = `영수증 #${receipt.id} 원본: ${receipt.file?.originalFileName || "업로드한 이미지"}`;
        image.onload = () => {
            if (!isCurrent()) return;
            panel.setAttribute("aria-busy", "false");
            status.textContent = "업로드한 사진과 추출 내용을 비교하세요.";
            image.hidden = false;
        };
        image.onerror = () => {
            if (!isCurrent()) return;
            panel.setAttribute("aria-busy", "false");
            status.textContent = "이미지를 표시할 수 없습니다. 파일이 손상되었을 수 있습니다.";
            status.classList.add("error");
            URL.revokeObjectURL(state.originalImageUrl);
            state.originalImageUrl = null;
            image.removeAttribute("src");
        };
        image.src = state.originalImageUrl;
    } catch (error) {
        if (error.name === "AbortError") return;
        if (error.status === 401) {
            notify(error.message, true);
        } else if (isCurrent()) {
            panel.setAttribute("aria-busy", "false");
            status.textContent = error.message;
            status.classList.add("error");
        }
    }
}

async function openDetail(id) {
    closeDetail();
    $("#detail-notice").hidden = true;
    const requestId = ++state.detailRequest;
    const receipt = await api(`/api/receipts/${id}`);
    if (!state.employee || requestId !== state.detailRequest) return;
    renderDetail(receipt);
    $("#receipt-dialog").showModal();
}

async function refreshDetail() {
    if (!state.detail) return;
    const requestId = ++state.detailRequest;
    const receipt = await api(`/api/receipts/${state.detail.id}`);
    if (!state.employee || requestId !== state.detailRequest) return;
    const wasPending = pendingJobs.has(state.detail.jobStatus);
    renderDetail(receipt);
    if (wasPending && !pendingJobs.has(receipt.jobStatus)) await loadReceipts(true);
}

function addLineItem(item = {}) {
    const row = node("div", null, "line-item");
    for (const [name, title] of [["name", "품목명"], ["quantity", "수량"], ["unitPrice", "단가"], ["amount", "금액"]]) {
        const label = node("label", title);
        const input = document.createElement("input");
        input.dataset.field = name;
        input.type = name === "name" ? "text" : "number";
        if (name !== "name") { input.min = "0"; input.step = name === "quantity" ? "1" : "0.01"; }
        input.value = item[name] ?? "";
        label.append(input);
        row.append(label);
    }
    const remove = node("button", "×");
    remove.type = "button";
    remove.setAttribute("aria-label", "구매 항목 삭제");
    remove.addEventListener("click", () => row.remove());
    row.append(remove);
    $("#line-items").append(row);
}

function renderDetail(receipt) {
    state.detail = receipt;
    $("#detail-id").textContent = `RECEIPT #${receipt.id}`;
    $("#detail-title").textContent = receipt.file?.originalFileName || "영수증 상세";
    const badge = statusBadge(receipt);
    $("#detail-status").textContent = badge.textContent;
    $("#detail-status").className = badge.className;
    $("#detail-job").textContent = jobNames[receipt.jobStatus] || "";
    const pending = pendingJobs.has(receipt.jobStatus);
    const terminal = ["APPROVED", "REJECTED"].includes(receipt.status);
    const canEdit = !pending && !terminal && receipt.status && receipt.ownerEmployeeId != null &&
        (state.employee.role !== "EMPLOYEE" || editableStatuses.has(receipt.status));
    $("#correction-fields").disabled = !canEdit;
    const canReview = canEdit && state.employee.role !== "EMPLOYEE" && receipt.ownerEmployeeId !== state.employee.id;
    const needsCorrectionBeforeApproval = ["NEEDS_RECAPTURE", "UNREADABLE"].includes(receipt.status);
    const hasApprovalFields = receipt.currentData?.shopName?.trim() && receipt.currentData.date &&
        Number(receipt.currentData.totalAmount) > 0;
    $("#decision-form").hidden = !canReview;
    $("#approve-receipt").disabled = needsCorrectionBeforeApproval || !hasApprovalFields;
    $("#decision-hint").textContent = needsCorrectionBeforeApproval ?
        "이 영수증은 반려할 수 있습니다. 승인하려면 원본을 확인하고 필요한 내용을 수정해 저장하세요." :
        !hasApprovalFields ? "승인하려면 상호명·사용일·0보다 큰 총 금액을 입력하고 저장하세요. 반려는 가능합니다." :
        "원본과 저장된 내용을 확인한 뒤 승인하거나 반려하세요.";
    $("#detail-hint").textContent = pending ? "영수증을 처리하고 있습니다. 잠시 후 ‘새로고침’을 눌러 결과를 확인하세요." :
        terminal ? "최종 처리된 영수증입니다. 내용을 변경할 수 없습니다." :
        canEdit ? "추출된 내용을 확인하세요. 변경된 내용은 저장 후 반영됩니다." : "현재 상태에서는 내용을 변경할 수 없습니다.";
    for (const key of correctionKeys) $("#correction-form").elements[key].value = receipt.currentData?.[key] ?? "";
    $("#line-items").replaceChildren();
    for (const item of receipt.currentData?.lineItems || []) addLineItem(item);
    $("#rule-results").replaceChildren();
    for (const rule of receipt.ruleResults || []) {
        const li = node("li");
        const labels = { PASS: "통과", FAIL: "확인 필요", NOT_APPLICABLE: "해당 없음" };
        const style = rule.outcome === "FAIL" ? "warning" : rule.outcome === "PASS" ? "success" : "";
        li.append(node("span", labels[rule.outcome] || rule.outcome, `badge ${style}`), node("span", rule.message));
        $("#rule-results").append(li);
    }
    if (!receipt.ruleResults?.length) $("#rule-results").append(node("li", "아직 검증 결과가 없습니다.", "muted"));
    $("#detail-json").textContent = JSON.stringify(receipt, null, 2);
}

async function loadAudit() {
    if (!state.detail) return;
    const id = state.detail.id;
    const events = await api(`/api/receipts/${id}/audit-events`);
    if (state.detail?.id !== id) return;
    $("#audit-events").replaceChildren();
    for (const event of events) {
        const li = node("li");
        li.append(node("strong", event.action), node("small", `${dateTime(event.occurredAt)} · ${event.actor}`));
        if (event.newStatus) li.append(node("span", `${statusNames[event.previousStatus] || "접수"} → ${statusNames[event.newStatus] || event.newStatus}`));
        const details = node("details");
        details.append(node("summary", "이력 내용"), node("pre", JSON.stringify(event.details, null, 2)));
        li.append(details);
        $("#audit-events").append(li);
    }
}

function onSubmit(selector, action) {
    $(selector).addEventListener("submit", (event) => {
        event.preventDefault();
        run(event.submitter, () => action(event));
    });
}

$("#login-tab").addEventListener("click", () => switchAuthTab(false));
$("#setup-tab").addEventListener("click", () => switchAuthTab(true));
onSubmit("#login-form", async () => {
    await api("/api/auth/login", { method: "POST", body: Object.fromEntries(new FormData($("#login-form"))) });
    $("#login-form").elements.password.value = "";
    await enterWorkspace(await api("/api/auth/me"));
});
onSubmit("#setup-form", async () => {
    const form = $("#setup-form");
    const values = Object.fromEntries(new FormData(form));
    if (values.password !== values.confirmPassword) throw new Error("비밀번호 확인 값이 일치하지 않습니다.");
    if (new TextEncoder().encode(values.password).length > 72) throw new Error("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
    await api("/api/auth/password", { method: "POST", body: { token: values.token.trim(), password: values.password } });
    form.reset();
    switchAuthTab(false);
    notify("비밀번호가 설정되었습니다. 발급받은 로그인 ID로 로그인하세요.");
});
$("#logout-button").addEventListener("click", (event) => run(event.currentTarget, async () => {
    await api("/api/auth/logout", { method: "POST" });
    endSession();
    notify("로그아웃했습니다.");
}));
document.querySelectorAll("[data-view]").forEach((button) => button.addEventListener("click", () => run(null, () => changeView(button.dataset.view))));
$("#receipt-file").addEventListener("change", () => {
    if (state.previewUrl) URL.revokeObjectURL(state.previewUrl);
    state.previewUrl = null;
    state.uploadKey = null;
    const file = $("#receipt-file").files[0];
    $("#file-preview").hidden = true;
    $("#file-name").textContent = file?.name || "영수증 이미지 선택";
    if (!file) return;
    if (file.size > 10 * 1024 * 1024 || !["image/jpeg", "image/png"].includes(file.type)) {
        clearFile();
        notify("10MB 이하의 JPG, PNG 이미지를 선택하세요.", true);
        return;
    }
    state.previewUrl = URL.createObjectURL(file);
    $("#file-preview").src = state.previewUrl;
    $("#file-preview").hidden = false;
});
onSubmit("#upload-form", async () => {
    // 전송 실패 후 재시도할 때는 같은 요청 키를 사용합니다.
    state.uploadKey ||= crypto.randomUUID();
    const accepted = await api("/api/receipts", { method: "POST", body: new FormData($("#upload-form")), headers: { "Idempotency-Key": state.uploadKey } });
    clearFile();
    state.page = 0;
    $("#filter-form").reset();
    notify(`영수증 #${accepted.receiptId}이 접수되었습니다. 처리 결과는 ‘새로고침’을 눌러 확인하세요.`);
    await loadReceipts();
    await openDetail(accepted.receiptId);
});
onSubmit("#filter-form", async () => { state.page = 0; await loadReceipts(); });
$("#refresh-list").addEventListener("click", (event) => run(event.currentTarget, () => loadReceipts()));
// 목록 렌더링에서 이전/다음 버튼 상태를 결정하므로 run()의 버튼 복구를 사용하지 않습니다.
$("#previous-page").addEventListener("click", () => { state.page = Math.max(0, state.page - 1); run(null, () => loadReceipts()); });
$("#next-page").addEventListener("click", () => { state.page++; run(null, () => loadReceipts()); });
$("#close-detail").addEventListener("click", closeDetail);
$("#receipt-dialog").addEventListener("cancel", (event) => { event.preventDefault(); closeDetail(); });
$("#refresh-detail").addEventListener("click", (event) => run(event.currentTarget, refreshDetail));
$("#show-original-image").addEventListener("click", toggleOriginalImage);
$("#refresh-audit").addEventListener("click", (event) => run(event.currentTarget, loadAudit));
$("#add-line-item").addEventListener("click", () => addLineItem());
onSubmit("#correction-form", async () => {
    const receipt = state.detail;
    if (!receipt) return;
    const body = { version: receipt.version, clearFields: [], lineItems: [] };
    for (const key of correctionKeys) {
        const value = $("#correction-form").elements[key].value.trim();
        if (value === "") body.clearFields.push(key);
        else body[key] = key === "totalAmount" ? Number(value) : value;
    }
    for (const row of $("#line-items").children) {
        const item = {};
        for (const input of row.querySelectorAll("input")) item[input.dataset.field] = input.value === "" ? null :
            input.dataset.field === "name" ? input.value : Number(input.value);
        body.lineItems.push(item);
    }
    const updated = await api(`/api/receipts/${receipt.id}/fields`, { method: "PATCH", body });
    if (state.detail?.id !== receipt.id) return;
    renderDetail(updated);
    notify("수정 내용을 저장했습니다.");
    await loadReceipts(true);
    await loadAudit();
});
onSubmit("#decision-form", async (event) => {
    const receipt = state.detail;
    if (!receipt || !event.submitter) return;
    const decision = event.submitter.value;
    const updated = await api(`/api/receipts/${receipt.id}/decision`, { method: "POST", body: {
        version: receipt.version, decision, note: $("#decision-form").elements.note.value.trim()
    } });
    if (state.detail?.id !== receipt.id) return;
    renderDetail(updated);
    notify(decision === "APPROVE" ? "승인했습니다." : "반려했습니다.");
    await loadReceipts(true);
    await loadAudit();
});
onSubmit("#issue-form", async () => {
    clearIssuedToken();
    const requestId = state.employeeTokenRequest;
    const issued = await api("/api/employees", { method: "POST", body: Object.fromEntries(new FormData($("#issue-form"))) });
    if (requestId !== state.employeeTokenRequest || state.employee?.role !== "ADMIN" || state.view !== "employees") return;
    $("#issue-form").reset();
    showSetupToken("issued", issued);
    notify("직원 계정을 발급했습니다. 아래 설정 토큰을 확인하세요.");
});
onSubmit("#reissue-form", async () => {
    clearIssuedToken();
    const requestId = state.employeeTokenRequest;
    const values = Object.fromEntries(new FormData($("#reissue-form")));
    const issued = await api(`/api/employees/${encodeURIComponent(values.employeeId)}/setup-token`, { method: "POST" });
    if (requestId !== state.employeeTokenRequest || state.employee?.role !== "ADMIN" || state.view !== "employees") return;
    $("#reissue-form").reset();
    showSetupToken("reissued", issued);
    notify("새 비밀번호 설정 토큰을 발급했습니다. 기존 토큰 대신 새 토큰을 전달하세요.");
});
$("#copy-token").addEventListener("click", (event) =>
    run(event.currentTarget, () => copySetupToken("#issued-token")));
$("#copy-reissued-token").addEventListener("click", (event) =>
    run(event.currentTarget, () => copySetupToken("#reissued-token")));
onSubmit("#active-form", async () => {
    const values = Object.fromEntries(new FormData($("#active-form")));
    const employee = await api(`/api/employees/${encodeURIComponent(values.employeeId)}/active`, { method: "PATCH", body: { active: values.active === "true" } });
    notify(`${employee.name} 계정을 ${employee.active ? "활성화" : "비활성화"}했습니다.`);
});

// 새로고침해도 Redis 세션이 유효하면 로그인 상태를 복원합니다.
async function initialize() {
    try { await enterWorkspace(await api("/api/auth/me")); }
    catch (error) { if (error.status !== 401 && error.name !== "AbortError") notify(error.message, true); }
}
initialize();
