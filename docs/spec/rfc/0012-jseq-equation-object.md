# RFC 0012: JSEQ Equation Object and MATH.VAF Container Specification

Status: draft

Observed / Established: 2026-09-28

Japanese version: [0012-jseq-equation-object.ja.md](0012-jseq-equation-object.ja.md)

## Abstract

In Ichitaro documents (JTD / JTT), mathematical formulas created by the JustSystems Equation Editor (e.g., "Hakadoru! Equation Maker" / JSEQ3) are stored within OLE2 substorages as **"JSEQ Equation ObjectBoxes (`JSEQ.Document.3`)"**.

This specification defines and standardizes the OLE2 container hierarchy, stream layout, and internal binary format of `JSEQ3Contents` (magic `"MATH.VAF"`), which houses the mathematical expression model.

Currently, the container hierarchy, stream inventory, header structure, fixed overhead invariant, and font table layout are confirmed and established. The AST opcode definitions for mathematical operators and font-specific glyph character mappings (cmap) are designated as Draft / Under Investigation.

---

## 1. CFB Container Structure and Storage Discovery

Equation objects reside within the `EmbedItems` directory of sheet-specific storages as `Embedding <id>` substorages.

### 1.1 Representative Storage Hierarchy

```text
/ObjectSheets/DocSheet/DOCS_XXXX/             (Storage: Sheet storage)
  └ EmbedItems/                               (Storage: Object directory storage)
      ├ EmbeddingInfo                         (Stream: Object catalog / CLSID tracking)
      ├ Embedding 1/                          (Storage: Equation Object 1)
      │   ├ \x01CompObj                       (Stream: OLE2 Component Info)
      │   ├ JSEQ3Contents                     (Stream: Equation Model / MATH.VAF)
      │   ├ \x03EmbeddedPress                 (Stream: Display Snapshot / JSSnapShot32)
      │   ├ \x03Contents                      (Stream: FORM Stub / 34 bytes)
      │   ├ \x01Ole                           (Stream: OLE 1.0 Header / 20 bytes)
      │   └ \x04JSRV_SegmentInformation       (Stream: Segment Info / 720 bytes)
      └ Embedding 2/                          (Storage: Equation Object 2)
```

### 1.2 Identification Rules

1. **Class Identifiers**:
   - Type name in `\x01CompObj`: `一太郎数式オブジェクト` (Ichitaro Equation Object)
   - User Type String: `JSEQ3`
   - CLSID / Class Name: `JS.EqtnCtrl.1`
   - ProgID: `JSEQ.Document.3`
2. **Discovery Rules**:
   - In accordance with RFC 0011 §1.2, traverse `Embedding <id>` storages under `EmbedItems` in normalized lowercase ascending order.
   - Identify equation objects via `\x01CompObj` or the `EmbeddingInfo` catalog matching `JSEQ.Document.3`.

---

## 2. Stream Inventory

The role and size specifications of streams within a JSEQ3 storage are as follows:

| Stream Name | Typical Size | Magic / Format | Description / Role |
| :--- | :--- | :--- | :--- |
| `\x01CompObj` | 99 B | OLE2 CompObj | Declares OLE class identity (`JS.EqtnCtrl.1` / `JSEQ3`) |
| `JSEQ3Contents` | ~3.6 - 7.8 KB | `MATH.VAF` (UTF-16LE) | **Core Equation Model** (Tree nodes, character codes, font definitions) |
| `\x03EmbeddedPress` | ~8.5 - 22 KB | `JSSnapShot32` (+ `GCI`) | Visual rendering snapshot for Ichitaro UI (GCI vector format) |
| `\x03Contents` | 34 B | `FORM` Stub | Empty stub for OLE container (no payload) |
| `\x01Ole` | 20 B | OLE1 Header | OLE 1.0 compatibility header |
| `\x04JSRV_SegmentInformation` | 720 B | Binary | Layout and segment coordination metadata |

> **Note**: When exported from Ichitaro to Microsoft Word (.doc / RTF), JSEQ objects are converted to WMF files containing `TEXTOUT` records. However, **no WMF exists inside JTD files** (`\x03Contents` is an empty 34-byte stub). Extracting equation text directly from JTD files requires parsing `JSEQ3Contents`.

---

## 3. JSEQ3Contents (MATH.VAF) Binary Specification

`JSEQ3Contents` serializes a proprietary hierarchical formula model (Vector Abstract Format) as UTF-16LE records.

### 3.1 Header Layout

The leading 32 bytes (`0x00` - `0x1F`) of the stream are defined as follows:

```text
Offset (Hex)  Type    Field Name       Description
0x00 - 0x19   Bytes   Magic            "MATH.VAF\0\0..." (UTF-16LE: 4D 00 41 00 54 00 48 00 2E 00 56 00 41 00 46 00 ...)
0x1A - 0x1B   u16     Reserved         0x0000
0x1C - 0x1D   u16     VersionFlags     0x0103 (Little-endian: 03 01)
0x1E - 0x1F   u16     BodyLength       Byte length of the formula body record area (L_body)
```

### 3.2 Fixed Overhead Invariant (1,162 Bytes)

Empirical observation across real-world corpora (verified on 27 of 29 equations) demonstrates a strict invariant between the total stream size $S_{total}$ and the recorded body length $L_{body}$ at offset `0x1E`:

$$S_{total} - L_{body} = 1,162 \text{ bytes}$$

These 1,162 bytes are distributed as:
1. **Fixed Header Block**: 32 bytes at the start.
2. **Fixed Footer / Font Table Block**: 1,130 bytes at the end.

> **Variant Note**:
> In 2 of 29 equations, variant differences of 1,334 bytes (+172 B) and 990 bytes (-172 B) were observed, corresponding to optional auxiliary style sections.

### 3.3 Trailing Font Name Table

The stream ends with a table of font names and style slot assignments referenced by the equation nodes:

- **Common Fonts**:
  - `Times New Roman` (Alphanumeric variables, digits)
  - `JustUnitMark` (Math symbols, operators, units)
  - `JustOubunMark` (Greek letters, extended symbols)
- **Shared Slot IDs**:
  - Constant slot IDs `0x16E6` and `0x177A` appear repeatedly across objects as style and layout anchors.

---

## 4. Under Investigation & Decryption Protocol

To reconstruct linear text suitable for full-text search (e.g., `f(x) = (a + b) / c`), research is active on the following areas:

### 4.1 Tree Node Opcode Structure (AST)

The formula model is serialized as a 2D Abstract Syntax Tree (AST).

- **Child Node Record**:
  - Sequences matching `[x][0x94][1][2][0][childID][1][x][0]` have been observed, where $x$ represents monotonic layout coordinates.
- **Challenges**:
  - Identification of container opcodes for fractions, radicals (square roots), superscripts, subscripts, and enclosures (parentheses/brackets).
  - Normalization of traversal order (DFS) for linear text conversion (e.g., numerator $\to$ denominator, base $\to$ superscript $\to$ subscript).

### 4.2 Font-Specific cmap (Character Mapping)

Glyph references under `JustUnitMark` and `JustOubunMark` use proprietary internal glyph indices (e.g., `b`, `R`, `j`, `1`) rather than standard Unicode code points.

#### Full-Palette Export Protocol (Automated cmap Derivation)

To derive this mapping dictionary comprehensively and safely without compromising proprietary document content:

1. **Synthetic Full-Palette Document Generation**:
   - Construct a synthetic JTD document embedding all available math operators, symbols, and Greek characters selectable from the Ichitaro equation GUI palette.
2. **Export to Word (.doc / RTF)**:
   - Execute Ichitaro's built-in Word export. Ichitaro's conversion engine translates JSEQ objects into WMF `TEXTOUT` records with ground-truth Unicode text.
3. **Automated 1:1 Mapping Extraction**:
   - Run a reconciliation script comparing `JSEQ3Contents` bytes from the synthetic JTD against the WMF `TEXTOUT` strings from the DOC/RTF file, mechanically generating the complete index-to-Unicode conversion table (`cmap`).

### 4.3 Handling Cashless Variants

A subset of equation objects omit the inline glyph cache records (mark records). The AST node traversal pipeline is designed to be the primary extraction path so that extraction succeeds regardless of cache presence.

---

## 5. Tika JTD+ Extraction Pipeline Concept

```text
[Embedding N / JSEQ3Contents]
             │
             ▼
    [MATH.VAF Header Verification] (Magic, 0x1E body length)
             │
             ▼
    [AST Node Parser] (Traverse variable body records)
             │
      ┌──────┴──────┐
      │             │
[Standard AlphaNum]  [Symbol Fonts] (JustUnitMark, etc.)
 (Unicode)          │
                    ▼
           [cmap Table Resolution] (Auto-derived map)
                    │
      ┌─────────────┘
      ▼
[Linear Text Formatter] (DFS flattening of fractions/subscripts)
      │
      ▼
(Tika XHTML SAX Stream: <p class="equation">)
```
