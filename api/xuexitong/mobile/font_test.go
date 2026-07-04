package mobile

import (
	"encoding/base64"
	"encoding/binary"
	"strings"
	"testing"
)

func TestXxtFontTablesJSONAndTranslate(t *testing.T) {
	rawGlyph := []byte{1, 2, 3, 4}
	glyfJSON := `{"` + xxtFontKeyFor(rawGlyph) + `":"uni4E00"}`
	cmapJSON := `{"uni4E00":19968}`
	tables, err := parseXxtFontTablesJSON([]byte(glyfJSON), []byte(cmapJSON))
	if err != nil {
		t.Fatalf("parse tables: %v", err)
	}
	mapping, err := translateXxtFont(minimalXxtTTF(rawGlyph), tables)
	if err != nil {
		t.Fatalf("translate: %v", err)
	}
	if got := mapping[rune(1)]; got != '一' {
		t.Fatalf("mapping[1]=%q", got)
	}
}

func TestDecodeXxtFontHTMLNoTablesIsNoop(t *testing.T) {
	ClearXxtFontTables()
	html := `<p>abc</p>`
	got := NormalizeXxtFontHTML(html)
	if got != html {
		t.Fatalf("got %q, want %q", got, html)
	}
}

func TestDecodeXxtFontHTMLPreservesMarkup(t *testing.T) {
	rawGlyph := []byte{1, 2, 3, 4}
	glyfJSON := `{"` + xxtFontKeyFor(rawGlyph) + `":"uni4E00"}`
	cmapJSON := `{"uni4E00":19968}`
	if err := SetXxtFontTablesJSON([]byte(glyfJSON), []byte(cmapJSON)); err != nil {
		t.Fatalf("set tables: %v", err)
	}
	defer ClearXxtFontTables()
	font := base64.StdEncoding.EncodeToString(minimalXxtTTF(rawGlyph))
	html := `<html><head><style>@font-face{src:url(data:font/font-ttf;base64,` + font + `)}</style></head><body><p><span>` + string(rune(1)) + `</span></p></body></html>`
	got := NormalizeXxtFontHTML(html)
	if !strings.Contains(got, `<span>一</span>`) {
		t.Fatalf("decoded html missing mapped text: %s", got)
	}
	if !strings.Contains(got, `<p><span>`) {
		t.Fatalf("decoded html did not preserve nested markup: %s", got)
	}
}

func minimalXxtTTF(glyph []byte) []byte {
	if len(glyph)%2 != 0 {
		glyph = append(glyph, 0)
	}
	head := make([]byte, 54)
	binary.BigEndian.PutUint16(head[50:52], 0)
	maxp := make([]byte, 6)
	binary.BigEndian.PutUint16(maxp[4:6], 2)
	loca := make([]byte, 6)
	binary.BigEndian.PutUint16(loca[0:2], 0)
	binary.BigEndian.PutUint16(loca[2:4], 0)
	binary.BigEndian.PutUint16(loca[4:6], uint16(len(glyph)/2))
	tables := []struct {
		tag  string
		data []byte
	}{
		{tag: "head", data: head},
		{tag: "maxp", data: maxp},
		{tag: "loca", data: loca},
		{tag: "glyf", data: glyph},
	}
	headerLen := 12 + len(tables)*16
	out := make([]byte, headerLen)
	binary.BigEndian.PutUint32(out[0:4], 0x00010000)
	binary.BigEndian.PutUint16(out[4:6], uint16(len(tables)))
	offset := headerLen
	for i, table := range tables {
		entry := out[12+i*16 : 12+(i+1)*16]
		copy(entry[0:4], table.tag)
		binary.BigEndian.PutUint32(entry[8:12], uint32(offset))
		binary.BigEndian.PutUint32(entry[12:16], uint32(len(table.data)))
		out = append(out, table.data...)
		offset += len(table.data)
	}
	return out
}
