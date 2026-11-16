"""Make two-second visual review sheets and verify and measure story masters."""
import io
import math
import re
import subprocess
from pathlib import Path

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont

ROOT = Path('/mnt/c/Users/Cybium/Desktop/.sw-qa/promo_v2')
FF = imageio_ffmpeg.get_ffmpeg_exe()
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'

for lang in ('en', 'hi'):
    path = ROOT/f'scanwhirl_story_{lang}.mp4'
    reader = imageio_ffmpeg.read_frames(str(path))
    meta = next(reader); reader.close()
    assert meta['size'] == (1080, 1920) and meta['fps'] == 30
    assert path.stat().st_size < 30_000_000
    decoded = subprocess.run([FF, '-hide_banner', '-i', str(path), '-filter_complex',
                              'ebur128=peak=true', '-f', 'null', '-'],
                             text=True, capture_output=True, check=True)
    assert 'Video: h264 (High)' in decoded.stderr and 'Audio: aac' in decoded.stderr
    assert not re.search(r'corrupt|invalid|error', decoded.stderr, re.I)
    (ROOT/f'loudness_{lang}.txt').write_text(decoded.stderr, encoding='utf8')
    print(lang, 'duration', meta['duration'], 'bytes', path.stat().st_size)
    print(decoded.stderr[decoded.stderr.rfind('Summary:'):])
    data = subprocess.check_output([FF, '-v', 'error', '-i', str(path), '-vf',
                                    'fps=1/2,scale=360:640', '-f', 'image2pipe',
                                    '-vcodec', 'mjpeg', '-'])
    # MJPEG files are concatenated JPEG byte streams.
    images = re.findall(b'\xff\xd8\xff.*?\xff\xd9', data, re.S)
    assert images
    columns = 6; tile_h = 670
    sheet = Image.new('RGB', (columns*360, math.ceil(len(images)/columns)*tile_h), '#091014')
    draw = ImageDraw.Draw(sheet); font = ImageFont.truetype(FONT, 24)
    for i, frame in enumerate(images):
        x = (i % columns)*360; y = (i // columns)*tile_h
        sheet.paste(Image.open(io.BytesIO(frame)).convert('RGB'), (x, y+30))
        draw.text((x+8,y+3), f'{i*2:.1f}s', font=font, fill='white')
    sheet.save(ROOT/f'contact_{lang}.png')
