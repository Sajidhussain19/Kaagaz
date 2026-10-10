"""Generate a Kotlin test fixture that mimics ML Kit line output for the fictional PDFs:
- paragraph text wraps exactly as rendered (one OCR line per visual line)
- table cells become separate lines, emitted column by column (as observed on device)
Coordinates are scaled to a 1240 px wide render."""
import pdfplumber, sys, json
S = 1240/595.2756
def kt(s): return s.replace('\\','\\\\').replace('"','\\"').replace('$','\\$')
def page_lines(p):
    out=[]; tables=p.find_tables()
    cells=[]
    for t in tables:
        rows=t.rows
        ncol=max(len(r.cells) for r in rows)
        for c in range(ncol):
            for r in rows:
                bb=r.cells[c] if c < len(r.cells) else None
                if not bb: continue
                txt=(p.crop(bb).extract_text() or '').strip()
                if not txt: continue
                ws=p.crop(bb).extract_words()
                x0=min(w['x0'] for w in ws); x1=max(w['x1'] for w in ws); t0=min(w['top'] for w in ws); b0=max(w['bottom'] for w in ws)
                cells.append((t.bbox[1], c, txt.replace('\n',' '), (x0,t0,x1,b0)))
    tb=[t.bbox for t in tables]
    def in_table(l): return any(l['top']>=b[1]-1 and l['bottom']<=b[3]+1 for b in tb)
    items=[]
    for l in p.extract_text_lines(x_tolerance=1.5):
        if in_table(l): continue
        items.append((l['top'], 0, l['text'], (l['x0'],l['top'],l['x1'],l['bottom'])))
    # table cells inserted at the table's top position, keeping column order
    for (top,c,txt,bb) in cells: items.append((top+0.001*c, 1, txt, bb))
    items.sort(key=lambda i:(i[0], i[1]))
    # stable: cells already in column order within same table top
    return [(txt,[round(v*S) for v in bb]) for (_,_,txt,bb) in items]
name=sys.argv[2]; f=sys.argv[1]
with pdfplumber.open(f) as pdf:
    pages=[page_lines(p) for p in pdf.pages]
print(f"    val {name}: List<OcrPageResult> = listOf(")
for i,ls in enumerate(pages,1):
    print(f"        page({i},")
    for txt,bb in ls:
        print(f"            \"{kt(txt)}\" at box({bb[0]}, {bb[1]}, {bb[2]}, {bb[3]}),")
    print("        ),")
print("    )")
