package mobile

import (
	"bytes"
	"crypto/md5"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strings"
	"sync"

	"golang.org/x/net/html"
)

type xxtFontTables struct {
	glyfHashed map[string]string
	cmap       map[string]rune
}

var (
	xxtFontMu          sync.RWMutex
	xxtFontTablesState *xxtFontTables
)

// SetXxtFontTablesJSON installs the font anti-scrape tables used by
// NormalizeXxtFontHTML. The host may load these from go-core's
// utils/assets/glyfHashed.json and cmap.json, then inject them into mobile-core.
func SetXxtFontTablesJSON(glyfJSON, cmapJSON []byte) error {
	tables, err := parseXxtFontTablesJSON(glyfJSON, cmapJSON)
	if err != nil {
		return err
	}
	xxtFontMu.Lock()
	xxtFontTablesState = tables
	xxtFontMu.Unlock()
	return nil
}

// ClearXxtFontTables removes injected font tables. It is mainly useful for
// tests and for hosts that want to release table memory.
func ClearXxtFontTables() {
	xxtFontMu.Lock()
	xxtFontTablesState = nil
	xxtFontMu.Unlock()
}

// NormalizeXxtFontHTML decodes xuexitong base64 TTF anti-scrape text when font
// tables have been injected. Missing font data or missing tables is a no-op.
func NormalizeXxtFontHTML(rawHTML string) string {
	out, ok, err := DecodeXxtFontHTML(rawHTML)
	if err != nil || !ok {
		return rawHTML
	}
	return out
}

// DecodeXxtFontHTML returns decoded HTML, whether a font was applied, and any
// parse error. It preserves DOM structure by replacing text nodes only.
func DecodeXxtFontHTML(rawHTML string) (string, bool, error) {
	tables := currentXxtFontTables()
	if tables == nil {
		return rawHTML, false, nil
	}
	fontBytes, ok, err := extractXxtFont(rawHTML)
	if err != nil || !ok {
		return rawHTML, ok, err
	}
	mapping, err := translateXxtFont(fontBytes, tables)
	if err != nil {
		return rawHTML, true, err
	}
	if len(mapping) == 0 {
		return rawHTML, true, nil
	}
	root, err := html.Parse(strings.NewReader(rawHTML))
	if err != nil {
		return rawHTML, true, err
	}
	replaceXxtFontTextNodes(root, mapping)
	var buf bytes.Buffer
	if err := html.Render(&buf, root); err != nil {
		return rawHTML, true, err
	}
	return buf.String(), true, nil
}

func parseXxtFontTablesJSON(glyfJSON, cmapJSON []byte) (*xxtFontTables, error) {
	var glyf map[string]string
	if err := json.Unmarshal(glyfJSON, &glyf); err != nil {
		return nil, fmt.Errorf("xuexitong font: glyf table parse failed: %w", err)
	}
	var cmapRaw map[string]json.Number
	dec := json.NewDecoder(bytes.NewReader(cmapJSON))
	dec.UseNumber()
	if err := dec.Decode(&cmapRaw); err != nil {
		return nil, fmt.Errorf("xuexitong font: cmap table parse failed: %w", err)
	}
	cmap := make(map[string]rune, len(cmapRaw))
	for key, val := range cmapRaw {
		n, err := val.Int64()
		if err != nil {
			return nil, fmt.Errorf("xuexitong font: cmap value %q parse failed: %w", key, err)
		}
		cmap[key] = rune(n)
	}
	return &xxtFontTables{glyfHashed: glyf, cmap: cmap}, nil
}

func currentXxtFontTables() *xxtFontTables {
	xxtFontMu.RLock()
	defer xxtFontMu.RUnlock()
	if xxtFontTablesState == nil {
		return nil
	}
	glyf := make(map[string]string, len(xxtFontTablesState.glyfHashed))
	for k, v := range xxtFontTablesState.glyfHashed {
		glyf[k] = v
	}
	cmap := make(map[string]rune, len(xxtFontTablesState.cmap))
	for k, v := range xxtFontTablesState.cmap {
		cmap[k] = v
	}
	return &xxtFontTables{glyfHashed: glyf, cmap: cmap}
}

func extractXxtFont(rawHTML string) ([]byte, bool, error) {
	re := regexp.MustCompile(`data:(?:application|font)/(?:x-)?font-ttf[^,]*,([A-Za-z0-9+/=]+)`)
	match := re.FindStringSubmatch(rawHTML)
	if len(match) == 0 {
		return nil, false, nil
	}
	fontBytes, err := base64.StdEncoding.DecodeString(match[1])
	if err != nil {
		return nil, true, fmt.Errorf("xuexitong font: base64 decode failed: %w", err)
	}
	return fontBytes, true, nil
}

func xxtFontKeyFor(data []byte) string {
	h1 := sha1.Sum(data)
	h2 := md5.Sum(data)
	return hex.EncodeToString(h1[:]) + "|" + hex.EncodeToString(h2[:])
}

type xxtTableRecord struct {
	Offset uint32
	Length uint32
}

type xxtTTFFile struct {
	src    []byte
	tables map[string]xxtTableRecord
}

func parseXxtTTF(b []byte) (*xxtTTFFile, error) {
	if len(b) < 12 {
		return nil, fmt.Errorf("xuexitong font: short ttf")
	}
	r := bytes.NewReader(b)
	var numTables uint16
	if _, err := r.Seek(4, io.SeekStart); err != nil {
		return nil, err
	}
	if err := binary.Read(r, binary.BigEndian, &numTables); err != nil {
		return nil, err
	}
	if _, err := r.Seek(6, io.SeekCurrent); err != nil {
		return nil, err
	}
	if len(b) < 12+int(numTables)*16 {
		return nil, fmt.Errorf("xuexitong font: short table directory")
	}
	tables := make(map[string]xxtTableRecord)
	for i := 0; i < int(numTables); i++ {
		tag := make([]byte, 4)
		if _, err := io.ReadFull(r, tag); err != nil {
			return nil, err
		}
		if _, err := r.Seek(4, io.SeekCurrent); err != nil {
			return nil, err
		}
		var off, length uint32
		if err := binary.Read(r, binary.BigEndian, &off); err != nil {
			return nil, err
		}
		if err := binary.Read(r, binary.BigEndian, &length); err != nil {
			return nil, err
		}
		if int64(off)+int64(length) > int64(len(b)) {
			return nil, fmt.Errorf("xuexitong font: table %s out of range", string(tag))
		}
		tables[string(tag)] = xxtTableRecord{Offset: off, Length: length}
	}
	return &xxtTTFFile{src: b, tables: tables}, nil
}

func (t *xxtTTFFile) table(tag string) ([]byte, error) {
	rec, ok := t.tables[tag]
	if !ok {
		return nil, fmt.Errorf("xuexitong font: missing table %s", tag)
	}
	return t.src[rec.Offset : rec.Offset+rec.Length], nil
}

func translateXxtFont(font []byte, tables *xxtFontTables) (map[rune]rune, error) {
	mapping := make(map[rune]rune)
	ttf, err := parseXxtTTF(font)
	if err != nil {
		return mapping, err
	}
	head, err := ttf.table("head")
	if err != nil {
		return mapping, err
	}
	maxp, err := ttf.table("maxp")
	if err != nil {
		return mapping, err
	}
	loca, err := ttf.table("loca")
	if err != nil {
		return mapping, err
	}
	glyf, err := ttf.table("glyf")
	if err != nil {
		return mapping, err
	}
	if len(head) < 52 || len(maxp) < 6 {
		return mapping, fmt.Errorf("xuexitong font: short head/maxp table")
	}
	locFormat := int16(binary.BigEndian.Uint16(head[50:52]))
	numGlyphs := binary.BigEndian.Uint16(maxp[4:6])
	offsets, err := parseXxtLoca(loca, locFormat != 0, numGlyphs)
	if err != nil {
		return mapping, err
	}
	for i := 0; i < int(numGlyphs); i++ {
		start, end := offsets[i], offsets[i+1]
		if end <= start || int(end) > len(glyf) {
			continue
		}
		raw := glyf[start:end]
		refGlyph, ok := tables.glyfHashed[xxtFontKeyFor(raw)]
		if !ok {
			continue
		}
		targetRune, ok := tables.cmap[refGlyph]
		if !ok {
			continue
		}
		mapping[rune(i)] = targetRune
	}
	return mapping, nil
}

func parseXxtLoca(loca []byte, long bool, numGlyphs uint16) ([]uint32, error) {
	offsets := make([]uint32, int(numGlyphs)+1)
	if long {
		if len(loca) < len(offsets)*4 {
			return nil, fmt.Errorf("xuexitong font: short loca table")
		}
		for i := range offsets {
			offsets[i] = binary.BigEndian.Uint32(loca[i*4:])
		}
		return offsets, nil
	}
	if len(loca) < len(offsets)*2 {
		return nil, fmt.Errorf("xuexitong font: short loca table")
	}
	for i := range offsets {
		offsets[i] = uint32(binary.BigEndian.Uint16(loca[i*2:])) * 2
	}
	return offsets, nil
}

func replaceXxtFontTextNodes(n *html.Node, mapping map[rune]rune) {
	if n.Type == html.TextNode {
		n.Data = replaceXxtFontRunes(n.Data, mapping)
		return
	}
	for child := n.FirstChild; child != nil; child = child.NextSibling {
		replaceXxtFontTextNodes(child, mapping)
	}
}

func replaceXxtFontRunes(text string, mapping map[rune]rune) string {
	var out strings.Builder
	out.Grow(len(text))
	for _, r := range text {
		if next, ok := mapping[r]; ok {
			out.WriteRune(next)
			continue
		}
		out.WriteRune(r)
	}
	return out.String()
}
