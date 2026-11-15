"""Decode the deliverables and verify the requested technical export settings."""
import argparse
import json
import re
import subprocess
from pathlib import Path

import imageio_ffmpeg
from PIL import Image


def verify(root):
    results = []
    for layout, size in [('9x16', (1080, 1920)), ('4x5', (1080, 1350))]:
        path = root / f'scanwhirl_early_access_{layout}.mp4'
        reader = imageio_ffmpeg.read_frames(str(path))
        meta = next(reader)
        reader.close()
        assert meta['size'] == size
        assert meta['duration'] == 26
        assert meta['fps'] == 30
        assert 'yuv420p' in meta['pix_fmt']
        assert path.stat().st_size <= 25_000_000
        process = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-hide_banner', '-i', str(path),
            '-progress', 'pipe:1', '-nostats', '-f', 'null', '-'], text=True, capture_output=True, check=True)
        input_description = process.stderr.split('Stream mapping:')[0]
        assert 'Video: h264 (High)' in input_description
        assert 'Audio:' not in input_description
        frames = int(re.findall(r'^frame=(\d+)$', process.stdout, re.MULTILINE)[-1])
        assert frames == 780
        assert not re.search(r'corrupt|invalid|error', process.stderr, re.I), process.stderr
        result = {'file': path.name, 'size_bytes': path.stat().st_size, 'dimensions': size,
                  'duration_seconds': 26, 'fps': 30, 'decoded_frames': frames,
                  'codec': 'H.264 High', 'pixel_format': 'yuv420p', 'audio': 'none'}
        results.append(result)
    assert Image.open(root / 'cover_9x16.png').size == (1080, 1920)
    assert Image.open(root / 'contact_9x16.png').size == (1296, 3708)
    (root / 'technical_checks.json').write_text(json.dumps(results, indent=2))
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    print(json.dumps(verify(parser.parse_args().root), indent=2))
