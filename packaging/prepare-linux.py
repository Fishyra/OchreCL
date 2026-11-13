#!/usr/bin/env python3
"""Fetch official Linux x64 JRE/OpenJFX artifacts and verify their published digests."""
from pathlib import Path
import concurrent.futures, hashlib, json, os, urllib.request
BASE=Path(__file__).resolve().parents[1]
CACHE=BASE/'linux-cache'; CACHE.mkdir(exist_ok=True)
PROXY=os.environ.get('OCHRE_DOWNLOAD_PROXY','http://127.0.0.1:7892')

def request(url):
    """Use the configured download connection without changing the user's system proxy."""
    return urllib.request.build_opener(urllib.request.ProxyHandler({'https':PROXY})).open(url,timeout=90)

def sha(path):
    """Hash a local artifact."""
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1048576),b''):digest.update(block)
    return digest.hexdigest()

def fetch(entry):
    """Stream into a temporary file, retaining only verified completed downloads."""
    path=CACHE/entry['name']
    if path.exists():
        data=path.read_bytes(); algorithm='sha256' if 'sha256' in entry else 'sha1'
        if hashlib.new(algorithm,data).hexdigest()==entry[algorithm]:entry['sha256']=sha(path);return entry
    pending=path.with_suffix(path.suffix+'.part')
    with request(entry['url']) as source,pending.open('wb') as output:
        while block:=source.read(262144):output.write(block)
    algorithm='sha256' if 'sha256' in entry else 'sha1'
    if hashlib.new(algorithm,pending.read_bytes()).hexdigest()!=entry[algorithm]:raise RuntimeError('Digest mismatch: '+entry['name'])
    entry['sha256']=sha(pending)
    pending.replace(path);print('Verified '+entry['name'],flush=True);return entry

def main():
    """Pin a glibc Linux x64 JRE and the same JavaFX release used by the other distributions."""
    with request('https://api.azul.com/metadata/v1/zulu/packages/8c8edd90-4447-47c0-b4dc-5dbbb94a93bf') as response:meta=json.load(response)
    (CACHE/'runtime-metadata.json').write_text(json.dumps(meta,indent=2))
    entries=[{'name':meta['name'],'url':meta['download_url'],'sha256':meta['sha256_hash']}]
    for module in ('base','graphics','controls'):
        name=f'javafx-{module}-21.0.8-linux.jar';url=f'https://repo.maven.apache.org/maven2/org/openjfx/javafx-{module}/21.0.8/{name}'
        with request(url+'.sha1') as response:digest=response.read().decode().strip().split()[0]
        entries.append({'name':name,'url':url,'sha1':digest})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(fetch,entries))
    (BASE/'packaging/linux-dependencies.json').write_text(json.dumps({'runtime':entries[0],'javafx':entries[1:]},indent=2)+'\n')
    print('Linux dependencies verified',flush=True)
if __name__=='__main__':main()
