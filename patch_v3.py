"""Advance the package version for the long-playback reliability fix."""
from pathlib import Path
path = Path(__file__).resolve().parent / 'decoded/apktool.yml'
text = path.read_text(encoding='utf-8')
assert text.count('versionCode: 2') == 1
assert text.count('versionName: 20261111-1518-skybox2') == 1
text = text.replace('versionCode: 2', 'versionCode: 3', 1)
text = text.replace('versionName: 20261111-1518-skybox2', 'versionName: 20261111-1518-skybox3', 1)
path.write_text(text, encoding='utf-8')
print('v3: upgrade version for buffering lifetime and network-recovery fix.')
