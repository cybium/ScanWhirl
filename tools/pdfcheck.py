"""Independent check of a protected PDF with PyMuPDF (not ScanWhirl code).

Usage (from PowerShell):
  wsl -d Ubuntu --cd /tmp -- python3 /mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/current/tools/pdfcheck.py <pdf path in /mnt/c/...> <password>
"""
import sys

import fitz  # PyMuPDF

path, password = sys.argv[1], sys.argv[2]
doc = fitz.open(path)
print("needs_password:", bool(doc.needs_pass))
print("encryption:", doc.metadata.get("encryption"))
print("wrong_password_opens:", bool(fitz.open(path).authenticate(password + "x")))
doc = fitz.open(path)
print("right_password_opens:", bool(doc.authenticate(password)))
print("pages:", doc.page_count)
if doc.page_count:
    pix = doc[0].get_pixmap(dpi=30)
    print("page1_renders:", pix.width > 0 and pix.height > 0)
