#!/usr/bin/env python3
"""외부 패키지 없이 재현 가능한 PNG와 로컬 테스트 관리자 자격 증명을 준비합니다."""
import argparse
import hashlib
import json
import random
import secrets
import struct
import zlib
from pathlib import Path

GENERATED = Path(__file__).resolve().parent / 'generated'


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--width', type=int, default=800)
    parser.add_argument('--height', type=int, default=1200)
    parser.add_argument('--seed', type=int, default=20261015)
    args = parser.parse_args()
    if not (600 <= args.width <= 1600 and 600 <= args.height <= 1600):
        parser.error('가로·세로는 각각 600~1600px이어야 합니다.')
    GENERATED.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)
    # 압축이 잘되는 단색 대신 픽셀 노이즈를 사용합니다. OCR 정확도 검증용 사진은 아닙니다.
    rows = b''.join(b'\x00' + rng.randbytes(args.width * 3) for _ in range(args.height))
    image = b'\x89PNG\r\n\x1a\n'
    image += chunk(b'IHDR', struct.pack('>IIBBBBB', args.width, args.height, 8, 2, 0, 0, 0))
    image += chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')
    (GENERATED / 'receipt.png').write_bytes(image)
    metadata = dict(width=args.width, height=args.height, seed=args.seed, bytes=len(image),
                    sha256=hashlib.sha256(image).hexdigest(), kind='synthetic-rgb-noise')
    (GENERATED / 'image.json').write_text(json.dumps(metadata, indent=2) + '\n')
    admin_file = GENERATED / 'admin.json'
    if not admin_file.exists():
        with admin_file.open('x') as file:
            admin_file.chmod(0o600)
            json.dump(dict(loginId='loadtest-admin', password=secrets.token_urlsafe(24)), file)
    grafana_file = GENERATED / 'grafana.env'
    if not grafana_file.exists():
        with grafana_file.open('x') as file:
            grafana_file.chmod(0o600)
            file.write('GF_SECURITY_ADMIN_PASSWORD=' + secrets.token_urlsafe(32) + '\n')
    print(f'PNG 준비: {args.width}x{args.height}, {len(image):,} bytes → {GENERATED / "receipt.png"}')


if __name__ == '__main__':
    main()
