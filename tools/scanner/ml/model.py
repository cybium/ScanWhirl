"""Tiny paper-segmentation network for Scanner X (from scratch, CPU-sized).

Design constraints come from the lane contract (PROGRAM/SCANNER_EVIDENCE + task ML-003):
  * must be small enough for on-device CPU inference (target 0.3-1.5 M params),
  * input 256x256 letterboxed frame (aspect kept, see :mod:`data_ml`),
  * two heads: a paper-mask logit map (128x128, stride 2) and one global "page present" logit,
  * depthwise-separable convolutions so the arithmetic stays small,
  * **no pretrained weights anywhere** -- every run starts from ``weights=None``.

Everything here is pure PyTorch; no torchvision model is imported, so an accidental
pretrained download is impossible.
"""

from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

NET_SIZE = 256
MASK_STRIDE = 2  # mask logits come out at 128x128

__all__ = [
    "NET_SIZE",
    "MASK_STRIDE",
    "TinyPageNet",
    "count_parameters",
    "output_size",
]


def output_size(net_size: int = NET_SIZE, stride: int = MASK_STRIDE) -> int:
    if net_size % stride:
        raise ValueError("net_size must be divisible by the mask stride")
    return net_size // stride


class DSConv(nn.Module):
    """Depthwise 3x3 + pointwise 1x1, each followed by BatchNorm and ReLU."""

    def __init__(self, in_ch: int, out_ch: int, stride: int = 1):
        super().__init__()
        self.dw = nn.Conv2d(in_ch, in_ch, 3, stride, 1, groups=in_ch, bias=False)
        self.pw = nn.Conv2d(in_ch, out_ch, 1, 1, 0, bias=False)
        self.bn1 = nn.BatchNorm2d(in_ch)
        self.bn2 = nn.BatchNorm2d(out_ch)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = F.relu(self.bn1(self.dw(x)), inplace=True)
        x = F.relu(self.bn2(self.pw(x)), inplace=True)
        return x


class Down(nn.Module):
    """Two separable convs with a stride-2 first conv (one encoder level)."""

    def __init__(self, in_ch: int, out_ch: int):
        super().__init__()
        self.block = nn.Sequential(DSConv(in_ch, out_ch, stride=2), DSConv(out_ch, out_ch))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.block(x)


class Up(nn.Module):
    """Nearest up x2, concat skip, then one separable conv (cheap decoder step)."""

    def __init__(self, in_ch: int, skip_ch: int, out_ch: int):
        super().__init__()
        self.conv = DSConv(in_ch + skip_ch, out_ch)

    def forward(self, x: torch.Tensor, skip: torch.Tensor) -> torch.Tensor:
        x = F.interpolate(x, size=skip.shape[-2:], mode="nearest")
        return self.conv(torch.cat([x, skip], dim=1))


class TinyPageNet(nn.Module):
    """Encoder/decoder paper segmenter with a global presence head.

    The default widths plus the bottleneck ``neck`` convolution give roughly 0.4-0.5 M
    parameters (printed by :func:`count_parameters`): the Android-cheap end of the
    agreed 0.3-1.5 M range. The neck is a full 3x3 conv on purpose -- at the 8x8
    bottleneck it buys real capacity for almost no CPU time, unlike widening the
    high-resolution levels.
    """

    def __init__(self, in_ch: int = 3, widths: tuple[int, ...] = (20, 40, 72, 112, 160),
                 neck: bool = True, presence_hidden: int = 64):
        super().__init__()
        w = tuple(int(v) for v in widths)
        if len(w) != 5:
            raise ValueError("widths must have 5 entries: stem + 4 encoder levels")
        stem_ch, c1, c2, c3, c4 = w
        self.stem = nn.Sequential(
            nn.Conv2d(in_ch, stem_ch, 3, stride=2, padding=1, bias=False),
            nn.BatchNorm2d(stem_ch),
            nn.ReLU(inplace=True),
        )
        self.down1 = Down(stem_ch, c1)
        self.down2 = Down(c1, c2)
        self.down3 = Down(c2, c3)
        self.down4 = Down(c3, c4)  # bottleneck, /32 of the input
        self.neck = nn.Sequential(
            nn.Conv2d(c4, c4, 3, padding=1, bias=False),
            nn.BatchNorm2d(c4),
            nn.ReLU(inplace=True),
        ) if neck else nn.Identity()

        # Presence head: average + max pooling of the bottleneck, so it sees both
        # "how much paper-like stuff" and "is there any".
        self.presence = nn.Sequential(
            nn.Linear(c4 * 2, presence_hidden),
            nn.ReLU(inplace=True),
            nn.Linear(presence_hidden, 1),
        )

        self.up3 = Up(c4, c3, c3)
        self.up2 = Up(c3, c2, c2)
        self.up1 = Up(c2, c1, c1)
        self.up0 = Up(c1, stem_ch, stem_ch)
        self.head = nn.Conv2d(stem_ch, 1, 1)

    def forward(self, x: torch.Tensor) -> dict[str, torch.Tensor]:
        s0 = self.stem(x)
        s1 = self.down1(s0)
        s2 = self.down2(s1)
        s3 = self.down3(s2)
        s4 = self.down4(s3)
        s4 = self.neck(s4)

        pooled = torch.cat([
            F.adaptive_avg_pool2d(s4, 1).flatten(1),
            F.adaptive_max_pool2d(s4, 1).flatten(1),
        ], dim=1)
        presence = self.presence(pooled).squeeze(1)  # (B,) logits

        d3 = self.up3(s4, s3)
        d2 = self.up2(d3, s2)
        d1 = self.up1(d2, s1)
        d0 = self.up0(d1, s0)
        # The stem already halves the input, so d0 is at NET_SIZE/MASK_STRIDE and the
        # 1x1 head gives mask logits at 128x128 for a 256x256 input (no second
        # downsample, no full-resolution head: that is where the CPU time would go).
        mask = self.head(d0)
        return {"mask": mask.squeeze(1), "presence": presence}


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


if __name__ == "__main__":  # tiny self-check: shapes + parameter count
    net = TinyPageNet().eval()
    with torch.no_grad():
        out = net(torch.zeros(2, 3, NET_SIZE, NET_SIZE))
    n = count_parameters(net)
    print(f"mask {tuple(out['mask'].shape)} presence {tuple(out['presence'].shape)} "
          f"params {n} ({n / 1e6:.3f} M)")
