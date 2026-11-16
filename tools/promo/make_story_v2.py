"""Build the approved bilingual story from reviewed captures and narration in WSL.

Raw captures and outputs live outside the repository. Edit shots.json with reviewed
in-points before rendering; no capture or app interaction is performed here.
"""
import json
import math
import subprocess
from pathlib import Path

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont
from make_promo import background

ROOT = Path('/mnt/c/Users/Cybium/Desktop/.sw-qa/promo_v2')
FF = imageio_ffmpeg.get_ffmpeg_exe()
FONT = str(ROOT/'fonts/NotoSansDevanagari-Bold.ttf')
CAPTIONS = {
    'en': ['HOOK', 'Scan to PDF in seconds', 'Read text: English & हिंदी',
           'Hide private info before you share', '35+ PDF tools', 'English | हिंदी',
           'No ads · No account · Pro ₹100 once'],
    'hi': ['आपका फ़ोन, अब PDF ऑफ़िस', 'सेकंडों में PDF', 'अंग्रेज़ी और हिंदी में टेक्स्ट',
           'शेयर से पहले निजी जानकारी छिपाएँ', '35 से ज़्यादा PDF टूल्स', 'English | हिंदी',
           'कोई विज्ञापन नहीं · कोई अकाउंट नहीं · Pro ₹100 एक बार'],
}

def run(*args):
    subprocess.run([FF, '-hide_banner', '-loglevel', 'error', '-y', *map(str, args)], check=True)

def duration(path):
    result = subprocess.check_output([str(ROOT/'bin/ffprobe'), '-v', 'error', '-show_entries',
                                      'format=duration', '-of', 'default=noprint_wrappers=1:nokey=1', str(path)], text=True)
    return float(result.strip())

def lines_for(draw, text, font, width):
    rows = ['']
    for word in text.split():
        candidate = (rows[-1] + ' ' + word).strip()
        if draw.textlength(candidate, font=font) > width and rows[-1]:
            rows.append(word)
        else:
            rows[-1] = candidate
    return rows

def overlay(lang, index):
    canvas = background(1920).convert('RGBA')
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle((185, 300, 895, 1879), 43, fill='#28d2e1')
    d.rounded_rectangle((190, 305, 890, 1874), 38, fill=(0, 0, 0, 0))
    size = 60
    while size > 34:
        font = ImageFont.truetype(FONT, size)
        font.set_variation_by_name('Bold')
        rows = lines_for(d, CAPTIONS[lang][index], font, 920)
        if len(rows) <= 2:
            break
        size -= 2
    for i, row in enumerate(rows):
        d.text((540, 160 + i * (size+9)), row, font=font,
               fill='#28d2e1' if index in (0, 4) else 'white', anchor='mt', stroke_width=1)
    out = ROOT/'edit_v2'/f'overlay_{lang}_{index}.png'
    canvas.save(out)
    return out

def end_card(lang):
    if lang == 'en': return ROOT/'cover_en.png'
    im = Image.open(ROOT/'cover_en.png').convert('RGB')
    d = ImageDraw.Draw(im)
    d.rectangle((100, 870, 980, 1010), fill='#091014')
    f = ImageFont.truetype(FONT, 54)
    f.set_variation_by_name('Bold')
    d.text((540, 940), 'जल्दी इस्तेमाल करें: हमें DM करें', font=f, fill='#28d2e1', anchor='mm')
    out = ROOT/'edit_v2'/'cover_hi.png'
    im.save(out)
    return out

def make(lang, shots):
    work = ROOT/'edit_v2'; work.mkdir(exist_ok=True)
    vo = [ROOT/f'vo_{lang}_{i:02}.mp3' for i in range(1, 9)]
    durations = [duration(p) for p in vo]
    lengths = [math.ceil((d + (.35 if i < 7 else 2.5))*30)/30 for i, d in enumerate(durations)]
    parts = []
    for i, shot in enumerate(shots):
        part = work/f'{lang}_{i:02}.mp4'
        source = ROOT/'raw_final'/shot['file']
        ol = overlay(lang, i)
        cuts = shot.get('cuts', [shot['start']])
        inputs = []
        for cut in cuts:
            if isinstance(cut, dict):
                inputs += ['-loop', '1', '-framerate', '30', '-i', ROOT/'raw_final'/cut['file']]
            else:
                inputs += ['-ss', cut, '-i', source]
        inputs += ['-loop', '1', '-framerate', '30', '-i', ol]
        cut_duration = lengths[i] / len(cuts)
        pieces = []
        for j in range(len(cuts)):
            motion = (",zoompan=z='min(zoom+0.0006,1.06)':"
                      "x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=1:s=700x1570:fps=30") if i in (0, 2, 3) else ''
            pieces.append(f'[{j}:v]fps=30,tpad=stop_mode=clone:stop_duration={cut_duration},'
                          f'trim=duration={cut_duration},setpts=PTS-STARTPTS,scale=700:1570,setsar=1{motion}[p{j}]')
        pieces.append(''.join(f'[p{j}]' for j in range(len(cuts))) +
                      f'concat=n={len(cuts)}:v=1:a=0[phone]')
        # The overlay has an alpha hole matching the rounded phone frame.
        filters = (f'color=c=black:s=1080x1920:r=30:d={lengths[i]}[base];'
                   + ';'.join(pieces) + ';'
                   '[base][phone]overlay=190:305:shortest=1[tmp];'
                   f'[tmp][{len(cuts)}:v]overlay=0:0:shortest=1,format=yuv420p[out]')
        run(*inputs,
            '-filter_complex', filters, '-map', '[out]', '-t', lengths[i], '-r', 30,
            '-c:v', 'libx264', '-profile:v', 'high', '-preset', 'veryfast', '-crf', '20', '-an', part)
        parts.append(part)
    part = work/f'{lang}_07.mp4'
    run('-loop', '1', '-framerate', '30', '-i', end_card(lang), '-t', lengths[7],
        '-c:v', 'libx264', '-profile:v', 'high', '-preset', 'veryfast', '-crf', '20',
        '-pix_fmt', 'yuv420p', part)
    parts.append(part)
    listing = work/f'{lang}_concat.txt'
    listing.write_text(''.join(f"file '{p.as_posix()}'\n" for p in parts))
    video = work/f'{lang}_video.mp4'
    run('-f', 'concat', '-safe', '0', '-i', listing, '-c', 'copy', video)
    total = sum(lengths)
    offsets = [sum(lengths[:i]) for i in range(8)]
    audio_inputs = []
    for path in vo: audio_inputs += ['-i', path]
    audio_inputs += ['-i', ROOT/'music_original.wav']
    filters = []
    for i, offset in enumerate(offsets):
        filters.append(f'[{i+1}:a]aresample=48000,adelay={round(offset*1000)}:all=1[v{i}]')
    filters.append(''.join(f'[v{i}]' for i in range(8)) + 'amix=inputs=8:normalize=0[voice]')
    speaking = '+'.join(f'between(t,{start:.3f},{start+d:.3f})' for start, d in zip(offsets, durations))
    filters.append(f"[9:a]volume='if({speaking},0.0631,0.1259)':eval=frame,afade=t=in:d=0.5,afade=t=out:st={total-1}:d=1[music]")
    filters.append('[voice][music]amix=inputs=2:duration=longest:normalize=0,loudnorm=I=-13.2:TP=-1.5:LRA=11[a]')
    out = ROOT/f'scanwhirl_story_{lang}.mp4'
    run('-i', video, *audio_inputs, '-filter_complex', ';'.join(filters), '-map', '0:v', '-map', '[a]',
        '-t', total, '-c:v', 'copy', '-c:a', 'aac', '-b:a', '128k', '-ac', '2', '-movflags', '+faststart', out)
    print(lang, 'line durations', durations, 'total', round(total, 2), flush=True)

if __name__ == '__main__':
    shots = json.loads((ROOT/'shots.json').read_text(encoding='utf8'))
    assert len(shots) == 7
    for language in ('en', 'hi'): make(language, shots)
