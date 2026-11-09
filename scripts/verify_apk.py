"""Check ABI, ZIP integrity and absence of development instrumentation."""
import re
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    assert apk.testzip() is None, "Corrupt APK"
    abis = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/")}
    assert abis == {"armeabi-v7a"}, abis
    for name in apk.namelist():
        if re.fullmatch(r"classes\d*\.dex", name):
            assert b"Lcom/smbplayer/checks/" not in apk.read(name), "Test class in APK"
print("APK integrity, armeabi-v7a and test isolation passed")
