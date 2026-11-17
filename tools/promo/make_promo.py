"""Reproducible ScanWhirl early-access promo; run using WSL Python (Pillow + imageio_ffmpeg).

Raw footage stays outside git. See SHOTS for the editorial plan and timing.
"""

import argparse
import json
import math
import subprocess
from functools import lru_cache
from pathlib import Path

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SHOTS = [
    ("hook", 2.5, ["Your phone is", "a PDF office."]),
    ("scan", 3.5, ["Scan to PDF", "in seconds"]),
    ("ocr", 2.5, ["Read text", "English & Hindi"]),
    ("private", 5.5, ["Hide private info", "before you share"]),
    ("tools", 4, ["40+ PDF tools"]),
    ("pro", 3, ["Pro once.", "No subscription."]),
    ("end", 5, ["Get early access"]),
]

# (source, in point, output duration). Static Android frames are explicitly held.
# Jump cuts remove waiting; no synthetic app actions or changed document text.
EDL = [
    ('01_home', 1.8, 2.5),
    ('02b_page', 1.95, 3.5),
    ('03_ocr', 2.1, 2.5),
    ('04b_private_find', 0, 1.3),
    ('04c_private_hide', 0, 1.2), ('04d_private_result', 0, 3),
    ('05_tools', .8, 4), ('06_pro', .5, 3),
]
SUB = {
    'hook': 'No ads. No account. Works offline.',
    'scan': 'Paper in. PDF out.',
    'ocr': 'Read text, right on your phone.',
    'private': 'Find it. Cover it. Share with confidence.',
    'tools': 'Merge. Compress. Protect. And more.',
    'pro': '₹100 one-time',
}
BOLD = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
REG = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
DEVA = '/mnt/c/Windows/Fonts/Nirmala.ttc'
CYAN, WHITE, MUTED = '#6ee6f0', '#f5f8fa', '#a8bfc6'
FF = imageio_ffmpeg.get_ffmpeg_exe()


def run(args):
    subprocess.run([FF, '-hide_banner', '-loglevel', 'error', '-y', *map(str, args)], check=True)


def font(size, bold=False):
    return ImageFont.truetype(BOLD if bold else REG, size)


def text(draw, xy, value, size, color=WHITE, bold=False, anchor='mt'):
    f = font(size, bold)
    assert draw.textlength(value, font=f) <= 900, value
    draw.text(xy, value, font=f, fill=color, anchor=anchor)


@lru_cache(maxsize=2)
def background(height):
    # Low-resolution procedural light field, upsampled once, avoids banded circles.
    small = Image.new('RGB', (270, height // 4))
    px = small.load()
    for y in range(small.height):
        for x in range(270):
            g = math.exp(-(((x - 175) / 150) ** 2 + ((y - 95) / 180) ** 2))
            h = math.exp(-(((x - 20) / 130) ** 2 + ((y - 340) / 150) ** 2))
            px[x, y] = (int(9 + g * 1), int(16 + g * 41 + h * 9), int(20 + g * 46 + h * 12))
    base = small.resize((1080, height), Image.Resampling.BICUBIC)
    d = ImageDraw.Draw(base)
    # Restrained brand geometry, away from text.
    d.arc((590, 40, 1330, 780), 120, 310, fill='#164751', width=2)
    d.arc((-350, height - 780, 390, height - 40), 270, 90, fill='#15343d', width=2)
    return base


def rounded_paste(canvas, image, box, radius=34):
    x, y, w, h = box
    image = image.resize((w, h), Image.Resampling.LANCZOS)
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius, fill=255)
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((x - 5, y - 5, x + w + 4, y + h + 4), radius + 5, fill='#28d2e1')
    canvas.paste(image, (x, y), mask)


@lru_cache(maxsize=24)
def phone_shadow(w, h):
    shadow = Image.new('RGBA', (w + 110, h + 110))
    ImageDraw.Draw(shadow).rounded_rectangle((42, 42, w + 68, h + 68), 45, fill=(0, 0, 0, 180))
    return shadow.filter(ImageFilter.GaussianBlur(23))


def prepare(root):
    work = root / 'edit'
    work.mkdir(exist_ok=True)
    parts = []
    for i, (name, start, duration) in enumerate(EDL):
        output = work / f'{i:02d}.mp4'
        source = root / 'raw' / f'{name}.mp4'
        reader = imageio_ffmpeg.read_frames(str(source))
        meta = next(reader)
        reader.close()
        filters = 'crop=1080:2232:0:108,setsar=1'
        if meta['duration'] == 0:
            # Android's static single-frame MP4 has no frame rate; give it a clock.
            still = work / f'{name}.png'
            run(['-i', source, '-frames:v', 1, still])
            source = still
            input_args = ['-loop', '1', '-framerate', '30']
        else:
            input_args = []
        # Decode to an explicit CFR clock, then select by integer frame index.
        # Holding the last decoded frame in Python also handles sparse VFR tails.
        frames = imageio_ffmpeg.read_frames(str(source), pix_fmt='rgb24',
            input_params=input_args, output_params=['-vf', 'fps=30,' + filters, '-fps_mode', 'passthrough'])
        next(frames)
        encoder = imageio_ffmpeg.write_frames(str(output), (1080, 2232), fps=30,
            codec='libx264', pix_fmt_in='rgb24', pix_fmt_out='yuv420p', macro_block_size=1,
            output_params=['-preset', 'fast', '-crf', '16'])
        encoder.send(None)
        last = None
        for index in range(round(start * 30) + round(duration * 30)):
            try:
                last = next(frames)
            except StopIteration:
                assert last is not None, source
            if index >= round(start * 30):
                encoder.send(last)
        frames.close()
        encoder.close()
        reader = imageio_ffmpeg.read_frames(str(output))
        assert abs(next(reader)['duration'] - duration) < .02, output
        reader.close()
        parts.append(output)
    listing = work / 'concat.txt'
    listing.write_text(''.join(f"file '{p.as_posix()}'\n" for p in parts))
    footage = work / 'footage.mp4'
    run(['-f', 'concat', '-safe', '0', '-i', listing, '-c', 'copy', footage])
    return footage


def scene_base(height, index):
    name, _, lines = SHOTS[index]
    image = background(height).copy()
    d = ImageDraw.Draw(image)
    compact = height == 1350
    text(d, (90, 120), 'SCANWHIRL  /  EARLY ACCESS', 23, CYAN, True, 'lt')
    y, size, gap = 163, 72, 82
    for j, line in enumerate(lines):
        text(d, (90, y + j * gap), line, size, CYAN if j == len(lines) - 1 else WHITE, True, 'lt')
    if name in ('hook', 'pro'):
        text(d, (90, 345), SUB[name], 30, MUTED, False, 'lt')
    # Quiet chapter marker: all graphic text remains inside the safe rectangle.
    text(d, (990, 120), f'0{index + 1}', 23, MUTED, True, 'rt')
    return image


def end_frame(height, icon, t):
    image = background(height).copy()
    d = ImageDraw.Draw(image)
    compact = height == 1350
    iy, iz = (180, 128) if compact else (365, 180)
    # 3% icon push over the held end card; text stays perfectly still.
    iz = int(iz * (1 + .03 * min(t / 5, 1)))
    mark = icon.resize((iz, iz), Image.Resampling.LANCZOS)
    mask = Image.new('L', (iz, iz), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, iz - 1, iz - 1), iz // 5, fill=255)
    image.paste(mark, ((1080 - iz) // 2, iy), mask)
    text(d, (540, 347 if compact else 603), 'ScanWhirl', 64 if compact else 78, WHITE, True)
    text(d, (540, 486 if compact else 796), 'Get early access', 78 if compact else 88, CYAN, True)
    text(d, (540, 590 if compact else 916), 'Free · Be one of the first', 35, MUTED)
    by = 665 if compact else 1030
    d.rounded_rectangle((315, by, 765, by + 100), 50, fill=CYAN)
    text(d, (540, by + 25), 'DM us to join', 40, '#091014', True)
    d.text((540, 922 if compact else 1340), 'Offline · No ads · English & हिंदी',
           font=ImageFont.truetype(DEVA, 34), fill=MUTED, anchor='mt')
    return image


def lens_crop(frame, name, local, height):
    # Native-resolution app regions; separate crops keep 4:5 content readable.
    compact = height == 1350
    if name == 'private':
        if local < 1.3:
            region = (0, 720, 1080, 1590) if compact else (0, 650, 1080, 2232)
        elif local < 2.5:
            region = (0, 1330, 1080, 2232)
        else:
            region = (90, 500 if compact else 400, 1000, 1230)
        return frame.crop(region)
    regions = {
        'hook': (0, 0, 1080, 1080 if compact else 2232),
        'scan': (0, 340, 1080, 1330 if compact else 1750),
        'ocr': (30, 180, 1050, 1050 if compact else 1700),
        'tools': (0, 200 if compact else 100, 1080, 1220 if compact else 1800),
        'pro': (0, 500, 1080, 1450 if compact else 1900),
    }
    return frame.crop(regions[name])


def render(root, footage, height, icon):
    layout = '9x16' if height == 1920 else '4x5'
    destination = root / f'scanwhirl_early_access_{layout}.mp4'
    encoder = subprocess.Popen([FF, '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
        '-s', f'1080x{height}', '-r', '30', '-i', '-', '-an', '-c:v', 'libx264',
        '-profile:v', 'high', '-level:v', '4.1', '-preset', 'fast', '-crf', '20',
        '-maxrate', '5500k', '-bufsize', '11000k', '-pix_fmt', 'yuv420p',
        '-movflags', '+faststart', str(destination)], stdin=subprocess.PIPE)
    reader = imageio_ffmpeg.read_frames(str(footage), pix_fmt='rgb24')
    metadata = next(reader)
    assert metadata['size'] == (1080, 2232)
    global_frame = 0
    for index, (name, duration, _) in enumerate(SHOTS):
        base = scene_base(height, index) if name != 'end' else None
        for n in range(round(duration * 30)):
            local = n / 30
            if name == 'end':
                image = end_frame(height, icon, local)
            else:
                frame = Image.frombytes('RGB', (1080, 2232), next(reader))
                image = base.copy()
                frame = lens_crop(frame, name, local, height)
                scale = 1 + .03 * n / max(1, duration * 30 - 1)
                w = round(860 * scale)
                h = round(frame.height * w / frame.width)
                box = ((1080 - w) // 2, 405, w, h)
                # Broad soft shadow, generated only at the small phone's size.
                x, y, w, h = box
                shadow = phone_shadow(w, h)
                image.paste(shadow, (x - 55, y - 42), shadow)
                rounded_paste(image, frame, box)
            encoder.stdin.write(image.tobytes())
            if global_frame == 15 and height == 1920:
                image.save(root / 'cover_9x16.png')
            global_frame += 1
        print(f'{layout}: {name} complete', flush=True)
    reader.close()
    encoder.stdin.close()
    assert encoder.wait() == 0
    assert global_frame == 780
    return destination


def contact_sheet(video, root, layout):
    reader = imageio_ffmpeg.read_frames(str(video), pix_fmt='rgb24',
        output_params=['-vf', r'select=not(mod(n\,15)),scale=216:-1', '-fps_mode', 'vfr'])
    next(reader)
    h = 384 if layout == '9x16' else 270
    frames = [Image.frombytes('RGB', (216, h), data) for data in reader]
    assert len(frames) == 52, len(frames)
    sheet = Image.new('RGB', (6 * 216, 9 * (h + 28)), '#091014')
    d = ImageDraw.Draw(sheet)
    for i, frame in enumerate(frames):
        x, y = i % 6 * 216, i // 6 * (h + 28)
        sheet.paste(frame, (x, y + 28))
        d.text((x + 7, y + 3), f'{i / 2:04.1f}s', font=font(18), fill=CYAN)
    sheet.save(root / f'contact_{layout}.png')
    for i in range(0, 9, 2):
        sheet.crop((0, i * (h + 28), 1296, min(i + 2, 9) * (h + 28))).save(root / 'edit' / f'review_{layout}_{i // 2 + 1}.jpg')


def write_notes(root, videos):
    report = []
    for video in videos:
        reader = imageio_ffmpeg.read_frames(str(video))
        metadata = next(reader)
        reader.close()
        assert metadata['duration'] == 26 and metadata['fps'] == 30
        assert video.stat().st_size < 25_000_000
        report.append({'file': video.name, 'bytes': video.stat().st_size, **metadata})
    (root / 'verification.json').write_text(json.dumps(report, indent=2))
    notes = '''# ScanWhirl early-access promo

## Final script — 26 seconds, silent, 30 fps

| Time | Main caption | Supporting caption |
|---|---|---|
| 0–2.5 | Your phone is / a PDF office. | No ads. No account. Works offline. |
| 2.5–6 | Scan to PDF / in seconds | |
| 6–8.5 | Read text / English & Hindi | |
| 8.5–14 | Hide private info / before you share | |
| 14–18 | 40+ PDF tools | |
| 18–21 | Pro once. / No subscription. | ₹100 one-time |
| 21–26 | ScanWhirl / Get early access | Free · Be one of the first / DM us to join / Offline · No ads · English & हिंदी |

## Footage and edit

Recorded only com.scanwhirl.app.demo on the Infinix phone: home scroll; opening the fictional Sharma Traders invoice and its page; Physics notes Text screen; Offer letter redaction controls, Find results, Hide selected and deselected black boxes; PDF-tools scroll; Pro offer and price screens. All raw takes are in raw/. All raw clips were sampled at five points for review (review_raw/); the single-frame Android hold is repeated in its sampling row.

Main edit uses the Pro overview, with the permitted ₹100 price typeset in the caption. The price-screen take is retained as an unused alternate. The tool scroll omits the coming-soon tools. App chrome remains genuine; system status/navigation bars are cropped consistently. Android screenrecord stops emitting frames on static screens, so the last genuine frame is held explicitly. No app UI or document content is fabricated.

Revision uses only existing clips; the phone was not touched. Hard cuts skip all document-opening and search waits. OCR lasts 2.5 seconds. Footage is 860–886 pixels wide, with 3% pushes and native-resolution close-ups of text and covered contact details. The 4:5 version uses separately chosen regions rather than cropping the vertical export. Main captions are 72 px; the end-card CTA is the largest text and holds five seconds. Added captions stay in the safe area; app footage can extend below it, as requested by the lead. No audio; add licensed music in Instagram if desired. Sparse Android footage is decoded to a 30-fps clock and selected by frame index, with explicit last-frame holds.

## Compromises and posting

- Scan sequence shows the settled fictional invoice page rather than a live camera capture.
- The end card renders हिंदी with the installed Windows Nirmala font and Pillow's shaping support. The OCR headline remains “English & Hindi”.
- App navigation and peripheral content are deliberately cropped to prioritize readable proof.
- The invitation is “DM us to join”; testers are added by email after contacting the team.
- The five-second end card is deliberate: viewers have time to understand the beta invitation.
- More time: record a staged live paper scan and add a licensed music bed with gentle action sounds.

## Phone restoration

Free-user testing switch verified ON after recording (it was observed OFF when first inspected). Temporary redaction edits discarded; no demo document overwritten. Demo-mode exit broadcast sent and sysui_demo_allowed set to 0. Returned the demo app to Library, then Android Home. No network toggles or purchase actions used.

## Reproduce (WSL)

```
python3 tools/promo/review_raw.py /mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/promo
python3 tools/promo/make_promo.py --root /mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/promo --store /mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/store
```

Requires Pillow and imageio_ffmpeg. Output media and intermediate files stay outside the repository. verification.json records sizes, dimensions, pixel format, frame rate and duration. contact_9x16.png samples the final encoded file every 0.5 seconds; contact_4x5.png is an extra review deliverable.
'''
    (root / 'NOTES.md').write_text(notes, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--store', type=Path, required=True)
    parser.add_argument('--reuse-footage', action='store_true')
    args = parser.parse_args()
    args.root.mkdir(exist_ok=True, parents=True)
    footage = args.root / 'edit' / 'footage.mp4' if args.reuse_footage else prepare(args.root)
    icon = Image.open(args.store / 'icon_512.png').convert('RGBA')
    videos = []
    for height, layout in [(1920, '9x16'), (1350, '4x5')]:
        video = render(args.root, footage, height, icon)
        contact_sheet(video, args.root, layout)
        videos.append(video)
    write_notes(args.root, videos)
    from verify_promo import verify
    verify(args.root)
    print('Done:', args.root, flush=True)


if __name__ == '__main__':
    main()
