"""Inspect and losslessly encode Android screencap's packed RGB(A) frames."""
import struct
import zlib


def raw_frame(data):
    if len(data) < 12:
        raise ValueError('Truncated screencap header')
    width, height, format = struct.unpack_from('<III', data)
    channels = {1: 4, 2: 4, 3: 3, 5: 4}.get(format)
    if not channels or not (0 < width <= 16384 and 0 < height <= 16384):
        raise ValueError(f'Unsupported screencap geometry/format: {width}x{height}, {format}')
    size = width * height * channels
    header = len(data) - size
    if header not in (12, 16):
        raise ValueError('Screencap pixel length does not match its geometry')
    pixels = data[header:]
    if format == 5:  # BGRA -> RGBA, preserving RGB components.
        pixels = bytearray(pixels)
        pixels[0::4], pixels[2::4] = pixels[2::4], pixels[0::4]
    if format == 2:  # RGBX has no alpha channel.
        pixels = bytearray(pixels)
        pixels[3::4] = b'\xff' * (width * height)
    return width, height, channels, pixels


def frame_content(frame):
    width, height, channels, pixels = frame
    # A small grid distinguishes an empty/near-black buffer from the prepared
    # shell. A lone cursor is insufficient. This is not a UI correctness test.
    samples = [max(pixels[(y * width + x) * channels:(y * width + x) * channels + 3])
               for y in range(0, height, max(1, height // 64))
               for x in range(0, width, max(1, width // 64))]
    lit = sum(value > 8 for value in samples) / len(samples)
    spread = max(samples) - min(samples)
    return {'width': width, 'height': height, 'litFraction': lit,
            'brightnessRange': spread, 'hasContent': lit >= .01 and spread >= 16}


def frame_png(frame):
    """Encode the same captured pixels without resizing, compositing or relabeling."""
    width, height, channels, pixels = frame
    def chunk(name, value):
        return struct.pack('>I', len(value)) + name + value + struct.pack('>I', zlib.crc32(name + value))
    stride = width * channels
    rows = b''.join(b'\0' + pixels[y * stride:(y + 1) * stride] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6 if channels == 4 else 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))
