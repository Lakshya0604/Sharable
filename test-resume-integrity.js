// Integration test: run with a server on :8099, node test-resume-integrity.js <PIN>. Needs /tmp/a.bin and /tmp/b.bin (70 MB each).
// const fs=require("fs"),c=require("crypto"),P=process.argv[2],H={"X-Pin":P};const B="http://127.0.0.1:8099/u/same.bin";
// const sha=b=>c.createHash("sha256").update(b).digest("hex");const key=s=>sha(s).slice(0,32);
// const put=(k,sz,off,buf,fin,hash)=>fetch(`${B}?key=${k}&size=${sz}&offset=${off}${fin?"&final=1":""}`,{method:"PUT",headers:{...H,"X-Sha256":hash||sha(buf)},body:buf});
// const head=async(k,sz)=>+(await fetch(`${B}?key=${k}&size=${sz}`,{method:"HEAD",headers:H})).headers.get("x-offset");
// (async()=>{const a=fs.readFileSync("/tmp/a.bin"),b=fs.readFileSync("/tmp/b.bin"),ka=key("a"),kb=key("b"),S=a.length;
// // A: first piece ok, second piece aborted mid-body
// let r=await put(ka,S,0,a.subarray(0,30e6));console.log("A piece1",r.status);
// const ac=new AbortController();const body=new ReadableStream({i:0,pull(cn){if(this.i<10){cn.enqueue(a.subarray(30e6+this.i*1e6,30e6+(this.i+1)*1e6));this.i++}else ac.abort()}});
// try{await fetch(`${B}?key=${ka}&size=${S}&offset=30000000`,{method:"PUT",headers:{...H,"X-Sha256":sha(a.subarray(30e6,60e6)),"Content-Length":30e6},body,duplex:"half",signal:ac.signal})}catch{console.log("A aborted mid-piece")}
// await new Promise(r=>setTimeout(r,400));console.log("A offset after abort (must be 30000000):",await head(ka,S));
// // B: same filename, different content, must have its own offset 0 and not touch A
// console.log("B offset (must be 0):",await head(kb,S));r=await put(kb,S,0,b.subarray(0,35e6));console.log("B piece1",r.status);
// // corrupted piece: wrong hash must be rejected, offset stays
// r=await put(kb,S,35e6,b.subarray(35e6),true,"0".repeat(64));console.log("B bad-hash piece",r.status,"(want 422) offset",await head(kb,S));
// // declared-size overflow
// r=await put(kb,S,35e6,Buffer.concat([b.subarray(35e6),Buffer.alloc(5)]),true);console.log("B overflow",r.status,"(want 413)");
// // finish both correctly
// r=await put(kb,S,35e6,b.subarray(35e6),true);console.log("B final",r.status,await r.text());
// r=await put(ka,S,30e6,a.subarray(30e6),true);console.log("A final",r.status,await r.text());
// // wrong offset
// r=await put(ka,S,0,a.subarray(0,10),false);console.log("stale offset after finish",r.status)})()
