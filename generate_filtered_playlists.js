#!/usr/bin/env node
// Build a language-restricted LibriVox catalogue without changing playlist.m3u.
const fs = require("fs");
const path = require("path");
const ROOT = process.cwd();
const OUT = path.join(ROOT, "filtered");
const ALLOWED = new Map([
  ["English", "English"], ["Bengali", "Bangla"], ["Hindi", "Hindi"],
  ["Urdu", "Urdu"], ["Arabic", "Arabic"], ["Turkish", "Turkish"]
]);
const AUDIO = /\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wma|aiff)(?:[?#].*)?$/i;
const IGNORE = new Set([".git", ".github", "node_modules", "playlists", "filtered"]);
const FOREIGN_PREFIXES = new Set(("afrikaans ancient_greek bisaya-cebuano bulgarian cantonese chinese church_slavonic croatian czech danish dutch esperanto finnish french galician german greek hebrew hungarian indonesian irish italian japanese javanese korean latin latvian lithuanian luxembourgish macedonian malay maori marathi middle_english multilingual norwegian old_english old_javanese persian polish portuguese romanian russian serbian slovak spanish swedish tagalog tamil ukrainian welsh yiddish").split(" "));
const PREFIX_LANGUAGE = {
  english:"English", bengali:"Bangla", bangla:"Bangla", hindi:"Hindi",
  urdu:"Urdu", arabic:"Arabic", turkish:"Turkish"
};
function walk(dir, rel="") {
  let out=[];
  for (const e of fs.readdirSync(dir,{withFileTypes:true})) {
    if(e.name.startsWith(".") || IGNORE.has(e.name)) continue;
    const abs=path.join(dir,e.name), r=rel ? rel+"/"+e.name : e.name;
    if(e.isDirectory()) out.push(...walk(abs,r));
    else if(e.isFile() && e.name.toLowerCase().endsWith(".m3u") && !["playlist.m3u","master.m3u"].includes(e.name.toLowerCase())) out.push({abs,rel:r});
  }
  return out;
}
const clean=s=>String(s||"").replace(/[\r\n\t]+/g," ").replace(/\s+/g," ").trim();
const slug=s=>String(s||"").normalize("NFKD").replace(/[\u0300-\u036f]/g,"").toLowerCase().replace(/[^a-z0-9]+/g,"-").replace(/^-|-$/g,"");
const pretty=s=>clean(String(s||"").replace(/[_-]+/g," ")).replace(/\b\w/g,c=>c.toUpperCase()) || "Unknown";
function metadata(rel) {
 const p=rel.split("/"), tax=p[0], stem=path.basename(rel,path.extname(rel));
 let language="",genre="",author="",title=pretty(stem.replace(/-by-.+$/i,""));
 if(tax==="by-language" && p.length>=3 && ALLOWED.has(p[1])) language=ALLOWED.get(p[1]);
 if(tax==="by-genre" && p.length>=3) genre=pretty(p[1]);
 if(tax==="by-author" && p.length>=4) { author=pretty(p[2].replace(/_\d+$/,"")); }
 return {language,genre,author,title,tax};
}
function parse(file) {
 const lines=fs.readFileSync(file.abs,"utf8").replace(/^\uFEFF/,"").split(/\r?\n/);
 const out=[]; let info="";
 for(const raw of lines) {
  const s=raw.trim(); if(!s) continue;
  if(s.startsWith("#EXTINF:")) {info=s;continue;}
  if(s.startsWith("#")) continue;
  if(/^https?:\/\//i.test(s)&&AUDIO.test(s)) {
   const comma=info.indexOf(",");
   const dur=info.startsWith("#EXTINF:") ? (info.slice(8,comma<0?undefined:comma).match(/^-?\d+/)||["-1"])[0] : "-1";
   const label=comma>=0?clean(info.slice(comma+1)):path.basename(s);
   out.push({url:s,duration:dur,label});
  }
  info="";
 }
 return out;
}
function urlLanguage(url, folderLanguage) {
 // Explicit language tokens in multilingual track filenames override collection folder.
 const file=decodeURIComponent(url.split(/[?#]/)[0].split("/").pop()||"").toLowerCase();
 const m=file.match(/^([a-z][a-z_-]{1,24})_/);
 if(m) {
  const token=m[1].replace(/-/g,"_");
  if(PREFIX_LANGUAGE[token]) return PREFIX_LANGUAGE[token];
  if(FOREIGN_PREFIXES.has(token)) return null;
 }
 return folderLanguage || null;
}
function safeAttr(s){return clean(s).replace(/"/g,"'").replace(/,/g," ");}
function writeM3U(file, rows, category) {
 fs.mkdirSync(path.dirname(file),{recursive:true});
 const lines=["#EXTM3U", "#PLAYLIST:LibriVox — selected languages"];
 for(const t of rows) lines.push('#EXTINF:'+t.duration+' group-title="'+safeAttr(category || t.genre || "Other")+'",'+clean(t.display || t.label),t.url);
 fs.writeFileSync(file,lines.join("\n")+"\n","utf8");
}
function main(){
 const files=walk(ROOT).sort((a,b)=>a.rel.localeCompare(b.rel));
 const tracks=new Map();
 for(const f of files) {
  const meta=metadata(f.rel);
  // Only language catalogues establish a track's language; other taxonomies enrich metadata.
  for(const row of parse(f)) {
   let t=tracks.get(row.url);
   if(!t){t={...row,language:"",genre:"",author:"",title:meta.title,genres:new Set()};tracks.set(row.url,t);}
   if(meta.language) {
    const lang=urlLanguage(row.url,meta.language);
    if(lang && ALLOWED.has([...ALLOWED.entries()].find(([,v])=>v===lang)?.[0] || lang)) t.language=lang;
   }
   if(meta.genre) t.genres.add(meta.genre);
   if(meta.author && !t.author) t.author=meta.author;
   if((!t.title || t.title==="Unknown") && meta.title) t.title=meta.title;
  }
 }
 const all=[...tracks.values()].filter(t=>ALLOWED.has([...ALLOWED.entries()].find(([,v])=>v===t.language)?.[0] || t.language));
 if(!all.length) throw new Error("No matching tracks; refusing to write empty playlists.");
 for(const t of all) {
  t.genre=[...t.genres].sort()[0]||"Other / Unclassified";
  t.display=(t.title||t.label)+(t.author?" — "+t.author:"")+" · "+t.label;
 }
 const sort=(a,b)=>(a.title||"").localeCompare(b.title||"",undefined,{sensitivity:"base",numeric:true})||a.url.localeCompare(b.url);
 all.sort((a,b)=>a.language.localeCompare(b.language)||sort(a,b));
 writeM3U(path.join(OUT,"playlist.m3u"),all,"Selected languages");
 for(const lang of ALLOWED.values()) writeM3U(path.join(OUT,"by-language",slug(lang)+".m3u"),all.filter(t=>t.language===lang).sort(sort),lang);
 const groups=[
  ["by-genre",t=>t.genre],["by-author",t=>t.author||"Unknown author"],["by-title",t=>(t.title||"Unknown title").slice(0,1).toUpperCase()]
 ];
 for(const [folder,key] of groups) {
  const names=[...new Set(all.map(key))].sort();
  for(const name of names) {
   const subset=all.filter(t=>key(t)===name).sort(sort);
   writeM3U(path.join(OUT,folder,slug(name)+".m3u"),subset,name);
  }
 }
 const summary=["# LibriVox — selected-language playlists","","Only these languages are included: English, Bangla (Bengali), Hindi, Urdu, Arabic, Turkish.","","The original playlist.m3u is left unchanged. Multilingual collections are filtered using language markers in audio filenames where present; ambiguous tracks without a detectable language marker inherit their source language catalogue.","","- [Filtered master](playlist.m3u)","- [By language](by-language/)","- [By genre](by-genre/)","- [By author](by-author/)","- [By title initial](by-title/)","","Unique audio URLs: "+all.length,"","Generated from source M3U files; audio is linked, not copied.",""].join("\n");
 fs.writeFileSync(path.join(OUT,"README.md"),summary,"utf8");
 console.log("Filtered unique audio URLs:",all.length);
}
main();
