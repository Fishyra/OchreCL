from pathlib import Path
import hashlib, zipfile
base = Path(__file__).resolve().parents[1]
version = "0.1.0-preview.1"
image = base / "builds" / ("OchreCL-" + version + "-windows-x64")
archive = image.with_suffix(".zip")
with zipfile.ZipFile(archive,"w",zipfile.ZIP_DEFLATED) as z:
    for p in sorted(image.rglob("*")):
        rel=p.relative_to(image)
        if p.is_file() and not any(x in {"data",".minecraft",".DS_Store"} for x in rel.parts):
            z.write(p,Path(image.name)/rel)
archive.with_suffix(".zip.sha256").write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+"  "+archive.name+"\n")
print(archive)
