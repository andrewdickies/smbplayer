"""Run deterministic host checks; requires Python 3.9+ and JDK 17 on PATH."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/smbplayer/tv"
CLASSES = [
    "PlaybackByteCache", "RangeReadAhead", "ForwardCache", "VideoRecovery",
    "DownloadRate", "MediaBufferIndex", "BufferIndexSupport", "Mp4BufferIndex",
    "MatroskaBufferIndex", "FragmentedMp4BufferIndex", "TvSeekPolicy",
    "FileDisplay", "PlaylistOrder",
]
CHECKS = [
    "BufferIndexCheck", "Mp4EdgeCheck", "RangeReadAheadCheck", "ForwardCacheCheck",
    "VideoRecoveryCheck", "DownloadRateCheck", "TvSeekPolicyCheck",
    "FileDisplayCheck", "PlaylistOrderCheck", "CacheRetentionCheck",
]


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    return str(Path(java_home) / "bin" / (name + suffix)) if java_home else name


def main():
    with tempfile.TemporaryDirectory(prefix="smbplayer-checks-") as output:
        sources = [SOURCE / (name + ".java") for name in CLASSES]
        sources += [ROOT / "checks" / (name + ".java") for name in CHECKS]
        subprocess.run([java_tool("javac"), "-encoding", "UTF-8", "--release", "8",
                        "-d", output, *map(str, sources)], check=True, cwd=ROOT)
        for name in CHECKS:
            print("Running " + name, flush=True)
            subprocess.run([java_tool("java"), "-Xmx128m", "-cp", output,
                            "com.smbplayer.tv." + name], check=True, cwd=ROOT)
    print("All %d host checks passed." % len(CHECKS))


if __name__ == "__main__":
    main()
