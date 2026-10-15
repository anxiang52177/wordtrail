"""生成离线词库并检查结构；仅使用 Python 标准库。"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
fields = ['english', 'phonetic', 'partOfSpeech', 'meaning', 'example', 'translation']
words = []
for line_number, line in enumerate((ROOT / 'tools/words.psv').read_text(encoding='utf-8').splitlines(), 1):
    if not line.strip():
        continue
    parts = line.split('|')
    assert len(parts) == 6, f'第 {line_number} 行字段数错误'
    assert all(part.strip() for part in parts), f'第 {line_number} 行存在空字段'
    word = dict(zip(fields, parts))
    word['id'] = word['english'].lower()
    assert re.fullmatch(r'[a-z][a-z0-9_-]{0,79}', word['id'])
    assert word['phonetic'].startswith('/') and word['phonetic'].endswith('/')
    assert re.search('[\u4e00-\u9fff]', word['meaning'])
    assert re.search('[\u4e00-\u9fff]', word['translation'])
    words.append(word)
assert len(words) >= 300, f'词条不足：{len(words)}'
assert len({w['id'] for w in words}) == len(words), '存在重复 ID'
output = ROOT / 'app/src/main/assets/words.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(words, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Validated and generated {len(words)} words: {output}')
