#!/usr/bin/env python3
"""LAN gateway for Ollama Mobile. Python 3.10+, no third-party packages."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import argparse, json, os, secrets, shutil, zipfile
import xml.etree.ElementTree as ET

TOOLS = [
 {"type":"function","function":{"name":"list_dir","description":"List files and folders in any absolute system directory path (e.g. 'D:/' or 'C:/Users/USER/Documents').","parameters":{"type":"object","required":["path"],"properties":{"path":{"type":"string","description":"Absolute directory path"}}}}},
 {"type":"function","function":{"name":"grep_search","description":"Search for text keywords recursively across text files in a directory path.","parameters":{"type":"object","required":["directory","keyword"],"properties":{"directory":{"type":"string","description":"Absolute directory path"},"keyword":{"type":"string","description":"Keyword to find"}}}}},
 {"type":"function","function":{"name":"read_system_file","description":"Read any text file from any absolute path on the system (Read-Only).","parameters":{"type":"object","required":["path"],"properties":{"path":{"type":"string","description":"Absolute file path"}}}}},
 {"type":"function","function":{"name":"copy_to_workspace","description":"Copy an external system file into the allowed sandbox workspace so it can be edited.","parameters":{"type":"object","required":["src_absolute_path","dest_relative_name"],"properties":{"src_absolute_path":{"type":"string","description":"Absolute source file path"},"dest_relative_name":{"type":"string","description":"Target base filename inside workspace"}}}}},
 {"type":"function","function":{"name":"edit_file","description":"Safely replace exact text in a file inside the allowed workspace sandbox.","parameters":{"type":"object","required":["path","old_text","new_text"],"properties":{"path":{"type":"string","description":"Relative path inside workspace"},"old_text":{"type":"string"},"new_text":{"type":"string"}}}}}
]

class Gateway:
 def __init__(self, ollama, root, token): self.ollama=ollama.rstrip('/'); self.root=Path(root).resolve(); self.token=token
 def path(self, rel):
  p=(self.root/rel).resolve()
  if p != self.root and self.root not in p.parents: raise ValueError('Path di luar workspace ditolak')
  return p
 def run_tool(self, name, args, allow_edits):
  if name=='list_dir':
   try:
    path_str = args.get('path','')
    if not path_str: return 'Error: path folder wajib diisi'
    d = Path(path_str).resolve()
    if not d.is_dir(): return f'Error: {d} bukan sebuah folder'
    items = []
    try:
     with os.scandir(str(d)) as entries:
      for entry in entries:
       try:
        suffix = '/' if entry.is_dir(follow_symlinks=False) else ''
        items.append(f'{entry.name}{suffix}')
       except (PermissionError, FileNotFoundError): pass
    except PermissionError:
     return f'Error: Akses folder {d} ditolak oleh sistem Windows (Access Denied).'
    items.sort(key=lambda s: s.lower())
    return '\n'.join(items) if items else '(Folder kosong)'
   except Exception as e: return f'Error list folder: {str(e)}'
  if name=='grep_search':
   try:
    dir_str = args.get('directory','')
    kw = args.get('keyword','').lower()
    if not dir_str: return 'Error: parameter directory wajib diisi'
    d = Path(dir_str).resolve()
    if not d.is_dir(): return f'Error: {d} bukan folder valid'
    matches = []
    for root, dirs, files in os.walk(str(d)):
     # Modifikasi list dirs inplace agar tidak menelusuri folder sistem tersembunyi
     # yang memicu Access Denied
     dirs[:] = [name for name in dirs if not name.startswith('.') and name.lower() not in ['system volume information', '$recycle.bin']]
     for file_name in files:
      p = Path(root) / file_name
      try:
       if p.is_file() and p.stat().st_size < 500_000:
        txt = p.read_text('utf-8', errors='ignore')
        if kw in txt.lower():
         matches.append(str(p))
         if len(matches) >= 25: break
      except Exception: pass
     if len(matches) >= 25: break
    return 'File yang cocok:\n' + '\n'.join(matches) if matches else 'Tidak ditemukan kata kunci yang cocok.'
   except Exception as e: return f'Error grep: {str(e)}'
  if name=='read_system_file':
   try:
    path_str = args.get('path','')
    if not path_str: return 'Error: parameter path berkas wajib diisi'
    f = Path(path_str).resolve()
    if not f.is_file(): return 'Error: berkas tidak ditemukan'
    ext = f.suffix.lower()

    # 1. Handle Word Document (.docx)
    if ext == '.docx':
     try:
      with zipfile.ZipFile(str(f)) as z:
       xml_content = z.read('word/document.xml')
       root = ET.fromstring(xml_content)
       texts = []
       for elem in root.iter():
        if elem.tag.endswith('t'):
         if elem.text: texts.append(elem.text)
       out = ''.join(texts)
       return out[:150000] if len(out) > 150000 else out
     except Exception as ex: return f'Error membaca file Word (.docx): {str(ex)}'

    # 2. Handle PowerPoint (.pptx)
    if ext == '.pptx':
     try:
      with zipfile.ZipFile(str(f)) as z:
       slide_texts = []
       slide_files = sorted([name for name in z.namelist() if name.startswith('ppt/slides/slide') and name.endswith('.xml')])
       for sf in slide_files:
        xml_content = z.read(sf)
        root = ET.fromstring(xml_content)
        texts = []
        for elem in root.iter():
         if elem.tag.endswith('t') and elem.text: texts.append(elem.text)
        if texts: slide_texts.append(f'--- Slide ({sf.split("/")[-1]}) ---\n' + ' '.join(texts))
       out = '\n\n'.join(slide_texts)
       return out[:150000] if len(out) > 150000 else out
     except Exception as ex: return f'Error membaca file PowerPoint (.pptx): {str(ex)}'

    # 3. Handle Excel Sheet (.xlsx)
    if ext == '.xlsx':
     try:
      with zipfile.ZipFile(str(f)) as z:
       # Ambil Shared Strings
       shared = []
       if 'xl/sharedStrings.xml' in z.namelist():
        root = ET.fromstring(z.read('xl/sharedStrings.xml'))
        for elem in root.iter():
         if elem.tag.endswith('t'): shared.append(elem.text or '')
       # Ambil Sheet1
       rows = {}
       if 'xl/worksheets/sheet1.xml' in z.namelist():
        root = ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
        for row_elem in root.iter():
         if row_elem.tag.endswith('row'):
          r_id = row_elem.attrib.get('r', '1')
          cells = []
          for c_elem in row_elem.iter():
           if c_elem.tag.endswith('c'):
            t_type = c_elem.attrib.get('t', '')
            val_elem = c_elem.find('{*}v') if c_elem.find('{*}v') is not None else c_elem.find('v')
            val = ''
            if val_elem is not None and val_elem.text:
             if t_type == 's':
              try: val = shared[int(val_elem.text)]
              except Exception: val = val_elem.text
             else: val = val_elem.text
            cells.append(val or '')
          if cells: rows[int(r_id)] = cells
       # Format sebagai tabel Markdown
       md_lines = []
       for r_idx in sorted(rows.keys()):
        cells = rows[r_idx]
        md_lines.append('| ' + ' | '.join(cells) + ' |')
        if r_idx == 1:
         md_lines.append('|' + '---|' * len(cells))
       out = '\n'.join(md_lines)
       return out[:150000] if len(out) > 150000 else out
     except Exception as ex: return f'Error membaca file Excel (.xlsx): {str(ex)}'

    # 4. Handle PDF (.pdf) via pypdf
    if ext == '.pdf':
     try:
      import pypdf
      reader = pypdf.PdfReader(str(f))
      pdf_texts = []
      for idx, page in enumerate(reader.pages):
       t = page.extract_text()
       if t: pdf_texts.append(f'[Halaman {idx+1}]\n{t}')
      out = '\n\n'.join(pdf_texts)
      return out[:150000] if len(out) > 150000 else out
     except ImportError:
      return 'Error: Pustaka "pypdf" belum terinstal di laptop host. Jalankan perintah "pip install pypdf" terlebih dahulu di terminal laptop Anda.'
     except Exception as ex: return f'Error membaca file PDF (.pdf): {str(ex)}'

    # 5. Handle Teks Standar Biasa
    if f.stat().st_size > 1_000_000: return 'Error: ukuran berkas lebih dari 1 MB'
    out = f.read_text('utf-8', errors='ignore')
    return out[:150000] if len(out) > 150000 else out
   except Exception as e: return f'Error membaca berkas: {str(e)}'
  if name=='copy_to_workspace':
   try:
    src = Path(args.get('src_absolute_path','')).resolve()
    if not src.is_file(): return 'Error: berkas sumber tidak ditemukan'
    dst_name = args.get('dest_relative_name','')
    if not dst_name: dst_name = src.name
    dst = (self.root / dst_name).resolve()
    if self.root not in dst.parents and dst != self.root: return 'Error: target salinan di luar workspace'
    shutil.copy2(src, dst)
    return f'Berhasil menyalin berkas {src} ke workspace sebagai {dst_name}. Sekarang Anda bisa mengeditnya!'
   except Exception as e: return f'Error menyalin berkas: {str(e)}'
  if name=='edit_file':
   if not allow_edits: return 'Ditolak: pengguna belum mengaktifkan izin edit file di aplikasi.'
   p=self.path(args.get('path',''))
   if not p.is_file(): return 'Error: file tidak ditemukan di workspace'
   old=args.get('old_text',''); new=args.get('new_text',''); text=p.read_text('utf-8')
   count=text.count(old)
   if not old or count!=1: return f'Error: old_text harus cocok tepat sekali; ditemukan {count}.'
   shutil.copy2(p, str(p)+'.bak')
   p.write_text(text.replace(old,new,1),'utf-8')
   return f'Berhasil mengedit {args.get("path")} di dalam workspace; backup dibuat sebagai .bak'
  return 'Error: tool tidak dikenal'
 def ollama_json(self, path, payload=None):
  data=None if payload is None else json.dumps(payload).encode()
  req=Request(self.ollama+path, data=data, headers={'Content-Type':'application/json'})
  with urlopen(req, timeout=180) as r: return json.loads(r.read())

class Handler(BaseHTTPRequestHandler):
 server_version='OllamaMobileGateway/1.0'
 def setup(self):
  super().setup()
  import socket
  try: self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
  except Exception: pass
 def log_message(self, fmt, *args): print(self.address_string(), fmt%args)
 def send_json(self, code, obj):
  raw=json.dumps(obj).encode(); self.send_response(code); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
 def auth(self):
  expected='Bearer '+self.server.gateway.token
  if not self.server.gateway.token or self.headers.get('Authorization')!=expected: self.send_json(401,{'error':'Token tidak valid'}); return False
  return True
 def do_GET(self):
  if not self.auth(): return
  if self.path!='/models': return self.send_json(404,{'error':'Not found'})
  try:
   result=self.server.gateway.ollama_json('/api/tags')
   self.send_json(200,{'models':[{'name':m.get('name',''),'size':m.get('size',0),'details':m.get('details',{})} for m in result.get('models',[])]})
  except Exception as e: self.send_json(502,{'error':str(e)})
 def event(self, kind, text):
  self.wfile.write((json.dumps({'type':kind,'text':text},ensure_ascii=False)+'\n').encode()); self.wfile.flush()
 def do_POST(self):
  if not self.auth(): return
  if self.path!='/chat': return self.send_json(404,{'error':'Not found'})
  try:
   n=int(self.headers.get('Content-Length','0')); body=json.loads(self.rfile.read(n))
   model=body.get('model',''); messages=body.get('messages',[]); allow=bool(body.get('allow_edits',False))
   mode=body.get('mode','chat').lower()
   if not model: return self.send_json(400,{'error':'Model wajib dipilih'})
   if mode not in ['chat', 'agent']: return self.send_json(400,{'error':'Mode tidak valid'})
   print(f'[{mode}] model={model}')
   self.send_response(200); self.send_header('Content-Type','application/x-ndjson; charset=utf-8'); self.end_headers()
   if mode == 'chat':
    req=Request(self.server.gateway.ollama+'/api/chat', data=json.dumps({'model':model,'messages':messages,'stream':True}).encode(), headers={'Content-Type':'application/json'})
    with urlopen(req, timeout=180) as r:
     for line in r:
      if line.strip():
       res=json.loads(line.decode('utf-8'))
       tk=res.get('message',{}).get('content','')
       if tk: self.event('token',tk)
    self.event('done','')
    return
   system={'role':'system','content':'Anda adalah asisten lokal dengan akses sistem file Windows. Anda dapat mencari dan membaca file di mana saja (C:/, D:/, dll). Namun, Anda HANYA bisa mengedit file yang sudah disalin ke folder workspace. Jika pengguna minta edit file sistem, gunakan copy_to_workspace terlebih dahulu.'}
   history=[system]+messages
   for _ in range(8):
    req=Request(self.server.gateway.ollama+'/api/chat', data=json.dumps({'model':model,'messages':history,'tools':TOOLS,'stream':True}).encode(), headers={'Content-Type':'application/json'})
    full_content = ""
    tool_calls = []
    is_tool_turn = False
    with urlopen(req, timeout=180) as r:
     for line in r:
      if line.strip():
       res=json.loads(line.decode('utf-8'))
       msg_chunk = res.get('message', {})
       tk = msg_chunk.get('content', '')
       if tk:
        full_content += tk
        if not is_tool_turn: self.event('token', tk)
       chunks_calls = msg_chunk.get('tool_calls') or []
       if chunks_calls: is_tool_turn = True
       for call in chunks_calls:
        fn = call.get('function', {})
        name = fn.get('name', '')
        args = fn.get('arguments', '')
        found = False
        for tc in tool_calls:
         if name and tc['function']['name'] == name:
          found = True
          if isinstance(args, dict): tc['function']['arguments'].update(args)
          elif isinstance(args, str):
           if isinstance(tc['function']['arguments'], str): tc['function']['arguments'] += args
           else: tc['function']['arguments'] = args
          break
        if not found:
         tool_calls.append({"function": {"name": name, "arguments": args}})
    if tool_calls:
     for tc in tool_calls:
      a = tc['function']['arguments']
      if isinstance(a, str) and a.strip():
       try: tc['function']['arguments'] = json.loads(a)
       except Exception: pass
     msg_obj = {'role': 'assistant', 'content': full_content, 'tool_calls': tool_calls}
    else:
     msg_obj = {'role': 'assistant', 'content': full_content}
    history.append(msg_obj)
    if not tool_calls:
     self.event('done', '')
     return
    for call in tool_calls:
     fn=call.get('function',{}); name=fn.get('name',''); args=fn.get('arguments',{})
     if isinstance(args,str): args=json.loads(args)
     path_arg=args.get('path','')
     print(f'[agent] model={model} tool={name} path={path_arg}')
     self.event('tool',f'Menjalankan {name}: {path_arg}')
     result=self.server.gateway.run_tool(name,args,allow)
     history.append({'role':'tool','tool_name':name,'content':result})
   self.event('error','Batas 8 putaran tool tercapai')
  except HTTPError as e:
   detail=e.read().decode('utf-8'); print(f'[error] Ollama {e.code}: {detail}')
   try: self.event('error',f'Ollama {e.code}: {detail}')
   except Exception: pass
  except (BrokenPipeError, ConnectionResetError): pass
  except Exception as e:
   import traceback; traceback.print_exc()
   print(f'[error] {str(e)}')
   try: self.event('error',str(e))
   except Exception: pass

class Server(ThreadingHTTPServer):
 def __init__(self, addr, gateway): super().__init__(addr,Handler); self.gateway=gateway

def main():
 ap=argparse.ArgumentParser(); ap.add_argument('--host',default='0.0.0.0'); ap.add_argument('--port',type=int,default=8765); ap.add_argument('--ollama',default='http://127.0.0.1:11434'); ap.add_argument('--workspace',default='./workspace'); ap.add_argument('--token',default=os.getenv('OLLAMA_MOBILE_TOKEN',''))
 a=ap.parse_args(); Path(a.workspace).mkdir(parents=True,exist_ok=True); token=a.token or secrets.token_urlsafe(24)
 print(f'Gateway: http://<IP-KOMPUTER>:{a.port}'); print(f'Token: {token}'); print(f'Workspace: {Path(a.workspace).resolve()}')
 Server((a.host,a.port),Gateway(a.ollama,a.workspace,token)).serve_forever()
if __name__=='__main__': main()
