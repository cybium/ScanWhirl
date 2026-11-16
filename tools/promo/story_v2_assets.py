"""Prepare the approved story v2 narration, fallback music, and end card in WSL."""
import concurrent.futures
import math
import subprocess
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path('/mnt/c/Users/Cybium/Desktop/.sw-qa/promo_v2')
ROOT.mkdir(exist_ok=True)
LINES = {
    'en': ('en-IN-NeerjaExpressiveNeural', [
        'Your phone is now a PDF office.', 'Turn paper into clean PDFs in seconds.',
        'Read the text, in English and Hindi.', 'Hide phone numbers and emails before you share.',
        'Merge, compress, protect. Thirty-five plus tools.', 'Use it in English or Hindi.',
        'No ads. No account. Pro is a one-time hundred rupees.', 'Want early access? DM us.']),
    'hi': ('hi-IN-SwaraNeural', [
        'आपका फ़ोन अब एक PDF ऑफ़िस है।', 'कागज़ को सेकंडों में साफ़ PDF बनाइए।',
        'अंग्रेज़ी और हिंदी में टेक्स्ट पढ़िए।', 'शेयर करने से पहले फ़ोन नंबर और ईमेल छिपाइए।',
        'मर्ज, कंप्रेस, प्रोटेक्ट। पैंतीस से ज़्यादा टूल्स।', 'ऐप अंग्रेज़ी या हिंदी में चलाइए।',
        'कोई विज्ञापन नहीं, कोई अकाउंट नहीं। Pro सिर्फ़ एक बार सौ रुपये।', 'जल्दी इस्तेमाल करना है? हमें DM कीजिए।'])}

def speech(job):
    lang, voice, i, line = job
    out = ROOT / f'vo_{lang}_{i+1:02}.mp3'
    subprocess.run([str(Path.home()/'swtts/bin/edge-tts'), '--voice', voice, '--rate=+5%', '--text', line, '--write-media', str(out)], check=True)

def music():
    sr = 44100
    t = np.arange(sr * 80) / sr
    audio = np.zeros_like(t)
    beat = 60 / 105
    notes = [261.63, 329.63, 392, 329.63, 293.66, 349.23, 440, 349.23]
    for j in range(int(80 / beat) + 1):
        start = int(j * beat * sr)
        n = min(int(beat * sr), len(t) - start)
        if n <= 0: break
        x = np.arange(n) / sr
        audio[start:start+n] += .12 * np.sin(2 * np.pi * notes[j % 8] * x) * np.exp(-5*x)
    for f in (130.81, 164.81, 196):
        audio += .025 * np.sin(2*np.pi*f*t) * (0.65 + .35*np.sin(2*np.pi*t/8))
    pcm = (np.clip(audio, -1, 1) * 32767).astype('<i2')
    with wave.open(str(ROOT/'music_original.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())

def card():
    im = Image.new('RGB', (1080, 1920), '#091014')
    d = ImageDraw.Draw(im)
    icon = Image.open('/mnt/c/Users/Cybium/Desktop/ScanWhirl/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png').convert('RGBA').resize((260,260))
    im.paste(icon, (410, 420), icon)
    font = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
    dev = str(ROOT/'fonts/NotoSansDevanagari-Bold.ttf')
    d.text((540, 750), 'ScanWhirl', font=ImageFont.truetype(font, 100), fill='white', anchor='mm')
    d.text((540, 940), 'Early access: DM us', font=ImageFont.truetype(font, 62), fill='#28d2e1', anchor='mm')
    d.text((540, 1080), 'English & हिंदी · No ads · Nothing uploaded', font=ImageFont.truetype(dev, 34), fill='white', anchor='mm')
    im.save(ROOT/'cover_en.png')

if __name__ == '__main__':
    music(); card()
    jobs = [(lang, voice, i, line) for lang, (voice, lines) in LINES.items() for i, line in enumerate(lines)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(speech, jobs))
