"""Print the failure messages from Gradle JUnit XML results.
Usage (WSL): python3 tools/test-failures.py /home/cybium/swbuild/main/app/build/test-results/testDebugUnitTest
"""
import glob
import html
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."
for path in sorted(glob.glob(os.path.join(root, "*.xml"))):
    text = open(path, encoding="utf-8", errors="replace").read()
    for case in re.finditer(r'<testcase name="([^"]*)" classname="([^"]*)"[^>]*>(.*?)</testcase>', text, re.S):
        failure = re.search(r'<failure message="([^"]*)"', case.group(3), re.S)
        if failure:
            print("FAIL %s > %s" % (case.group(2).split(".")[-1], html.unescape(case.group(1))))
            print(html.unescape(failure.group(1))[:3000])
            print("-" * 60)
