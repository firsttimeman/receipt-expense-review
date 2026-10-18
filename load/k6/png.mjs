// 픽셀은 유지하고 유효한 PNG tEXt 청크를 넣어 요청마다 이미지 SHA-256을 다르게 합니다.
// 파일명만 바꾸면 서버의 이미지 중복 감지에 걸리므로 실제 바이트를 바꿉니다.
export function uniquePng(source, marker) {
    const original = new Uint8Array(source);
    if (original.length < 20 || original[0] !== 137 || original[1] !== 80 || original[2] !== 78 || original[3] !== 71
        || String.fromCharCode(...original.slice(-8, -4)) !== 'IEND') {
        throw new Error('generate.py로 생성한 PNG가 필요합니다.');
    }
    if (!/^[a-zA-Z0-9:-]+$/.test(marker)) throw new Error('이미지 식별자는 영문·숫자·콜론·하이픈만 허용합니다.');
    const text = 'loadtest\0' + marker;
    const chunk = new Uint8Array(text.length + 12);
    const view = new DataView(chunk.buffer);
    view.setUint32(0, text.length);
    chunk.set([116, 69, 88, 116], 4); // tEXt
    for (let i = 0; i < text.length; i++) chunk[8 + i] = text.charCodeAt(i);
    let crc = 0xffffffff;
    for (const byte of chunk.subarray(4, 8 + text.length)) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    view.setUint32(8 + text.length, (crc ^ 0xffffffff) >>> 0);
    const result = new Uint8Array(original.length + chunk.length);
    result.set(original.subarray(0, -12));
    result.set(chunk, original.length - 12);
    result.set(original.subarray(-12), original.length - 12 + chunk.length);
    return result.buffer;
}
