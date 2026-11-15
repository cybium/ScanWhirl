"""Extract time-labelled sampling sheets from every raw clip for visual review."""
import argparse
import io
import subprocess
from pathlib import Path

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser()
parser.add_argument('root', type=Path)
args = parser.parse_args()
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
out = args.root / 'review_raw'
out.mkdir(exist_ok=True)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 17)
rows = []
for clip in sorted((args.root / 'raw').glob('*.mp4')):
    reader = imageio_ffmpeg.read_frames(str(clip))
    meta = next(reader)
    reader.close()
    duration = meta['duration']
    row = Image.new('RGB', (1080, 530), '#091014')
    d = ImageDraw.Draw(row)
    for col, time in enumerate([0, duration * .25, duration * .5, duration * .75, max(0, duration - .1)]):
        filters = (f'tpad=stop_mode=clone:stop_duration=1,fps=30,trim=start={time},scale=216:492'
                   if duration else 'scale=216:492')
        data = subprocess.check_output([ffmpeg, '-v', 'error', '-i', str(clip), '-vf', filters,
            '-frames:v', '1', '-f', 'image2pipe', '-vcodec', 'png', '-'])
        image = Image.open(io.BytesIO(data)).convert('RGB')
        row.paste(image, (col * 216, 38))
        d.text((col * 216 + 3, 20), f'{time:.2f}s', font=font, fill='white')
    d.text((3, 0), clip.name, font=font, fill='#6ee6f0')
    row.save(out / f'{clip.stem}.jpg')
    rows.append(row)
for i in range(0, len(rows), 4):
    sheet = Image.new('RGB', (1080, 530 * len(rows[i:i + 4])), '#091014')
    for j, row in enumerate(rows[i:i + 4]):
        sheet.paste(row, (0, j * 530))
    sheet.save(out / f'sheet_{i // 4 + 1}.jpg')
print(out)
