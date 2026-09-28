# RFC 0013: Rule Flow Reconstruction Model and Table SAX Projection Specification (HTML Table / Markdown Contract)

Status: draft

Observed / Established: 2026-09-28
Updated: 2026-09-29 (Controlled experiments and HTML ground-truth verification: advance-axis span resolution, Blank Wrap inter-span markers, coalesce constant ≈6.5 verified, PaperMark orientation discriminator (6/6), rules never span a page — per-page table close, and open-end = advance-exit law)

Japanese version: [0013-structured-sax-flow-contract.ja.md](0013-structured-sax-flow-contract.ja.md)

## Abstract

In Ichitaro documents (JTD / JTT), **"Rules (Keisen)"** drawn on the canvas are neither hierarchical table cells (Box Model: `Document → Table → Row → Cell`) like Microsoft Word, nor standalone graphical text boxes ("LayoutBoxes").

In Ichitaro, **"Rules" and "Boxes (LayoutBoxes / ObjectBoxes)" are fundamentally distinct concepts**:
* **Boxes (LayoutBoxes / ObjectBoxes)**: Standalone rectangular containers anchored on paper coordinates, managed strictly in separate streams (`/LayoutBoxText`, `/LayoutBox`, `/Frame`, or OLE2 substorages) isolated from the main document body.
* **Rules (Keisen)**: Layout mechanisms drawn directly within the main text stream (`/DocumentText`). Drawing a vertical rule erects an imperative boundary wall at a specified coordinate within the line grid, partitioning the text flow into distinct spans that wrap independently.

Ichitaro possesses no native container object representing a "Table" or a "Cell". Instead, a visual "table" emerges purely when vertical rule walls recur at identical coordinates across consecutive lines.

This specification standardizes the **flow reconstruction model, table state machine, and XHTML / Markdown output contracts** required to reverse-engineer and synthesize higher-order DOM structures (`<table>`, `<tr>`, `<td>`) for Apache Tika 4.0.0 from **text streams partitioned by rules**.

At present, the flow event vocabulary, plain text invariants, span coalesce rules, **definitive row boundary detection via `0x000e` (Row Advance)**, **exact span count arithmetic ($w_5 = 4(n-1)+3$)**, **inline rule style encoding in line headers (in-line vs. inter-line, weight/color/dash styles, wall $x$-coordinate tags)**, **advance-axis span resolution**, and orientation invariance (identical behavior across horizontal and vertical scripts) are confirmed and accepted. **Two owner-confirmed laws now bound the model: rules never span a page (so every page is a table close, and "cross-page tables" are optical continuation of per-page redraws), and a rule's open end sits at the advance-direction exit. Span coordinates are confirmed to lie on the advance axis, not the paper-width axis.** Writing direction must be taken from the PaperMark orientation discriminator (verified 6/6), never from visual appearance (e.g. documents whose vertical look is fabricated by single-character wrapping). Automatic segmentation of continuous multi-form bundles at page boundaries and subtle contextual tail-word semantics remain under investigation as future extensions, along with the authoritative page-boundary trigger.

---

## 1. Design Philosophy and Mental Model (Flow Doctrine)

### 1.1 Box Model vs. Rule Stream Division Model

| Concept | Word / HTML (Box Model) | Ichitaro Rules (Stream Division Model) |
| :--- | :--- | :--- |
| **Fundamental Unit** | Cell (Box). The container exists first; text flows into it. | Line Grid (Flow). The continuous text stream is primary. |
| **Role of Vertical Rules** | Attributes defining cell boundaries. | Erects boundary walls across coordinates, causing independent wrapping. |
| **Intra-cell Wrapping** | Paragraph breaks or soft line wraps inside the cell container. | Progression to the next display line, reaffirming the active span. |
| **Role of Horizontal Rules**| Attributes defining row boundaries. | Horizontal break events or stream boundaries. |
| **Nature of Tables** | Explicit `<table>` container objects in the document tree. | Visual emergent grids arising from repeated boundary walls across lines. |

### 1.2 Redefining the Parser's Role

> **The parser's task is not merely to parse an existing table tree from Ichitaro.**
> **Its true role is to reverse-engineer and synthesize higher-order DOM structures (HTML Tables) from fragmented text streams partitioned by rule boundaries.**

The parser monitors span declarations and boundary events across the stream, actively reconstructing `<table>` / `<tr>` / `<td>` trees through span coalescence and stable grid detection.

---

## 2. Flow Event Vocabulary and Binary Invariants

Control codes and records within `/DocumentText` (RFC 0003) are abstracted into the following flow event vocabulary.

### 2.1 Event Taxonomy

| Code / Record | Flow Concept (RFC 0013) | Binary Characteristics | SAX / XHTML Projection |
| :--- | :--- | :--- | :--- |
| `0x001c` (class `0x0030`) | **Span Reaffirm** | 12-word fixed header. Carries left boundary $b_0$ and right boundary $b_1$. A jump in $b_0$ marks intra-line span transition. | `<td>` (Coalesce unit) |
| `0x000e` | **Row Advance** | Payload-free delimiter code. **Always occurs exactly once at line end**; never occurs during intra-line span transitions. | `</tr>` (Definitive row break) |
| `0x000a` | **Stream Wrap** | Payload-free line wrap code. Soft wrap within a span or at span transitions. In text-free ruled rows it also appears immediately after each span declaration as an inter-span advance marker (Blank Wrap; controlled crossing-rule sample). | `<br>` candidate; Blank Wrap emits nothing |
| `0x001c` (class `0x0010`) | **Line Header** | `w4=0x008f`: Column spec header ($n$ spans, style code, wall tags) / `w4=0x0020`, etc.: Paragraph style. | `<tr>` new row start, or `<p>` |
| `0x001c` (class `0x0020`) | **Flow Terminate** | Explicit table-to-paragraph transition record. | `</table>` close |
| `0x000c` | **Terminator / Structural Word** | Standalone page-break occurrences have not been confirmed: observed `0x000c` words decompose into cell-record length fields (len=12), embedded table-structure words, and stride-2 enumeration tables (consecutive integers 1..N stored at 2-word pitch; previously misread as terminator descriptors). | (none for v1; reserved) |

### 2.2 Plain Text Invariants

The introduction of structured SAX events must strictly preserve the following plain text invariants:

* **Text Completeness Invariant**: The character sequence emitted by the parser and extracted via the `-t` fast path must match pre-structure plain text byte-for-byte.
* **Order Preservation**: Text fragmented across spans must be concatenated in natural stream traversal order (physical line progression and span reading order), preventing omissions or duplication.

### 2.3 Orientation Invariance

* Ichitaro coordinates and flow events are normalized in orientation-relative space.
* In vertical-writing documents, line header style codes (`0x08`, `0x14`, `0x13`), Reaffirm loops, span gaps (gap=2), and row advances (`0x000e`) exhibit **identical byte values and identical behavioral semantics** to horizontal documents.
* **Appearance is not orientation (lesson from reference sample, owner-confirmed)**: the reference sample is horizontally set; its vertical appearance is fabricated by single-character-per-line wrapping inside one-character-wide enclosed spaces (enclosure width histogram: $20$ units ≈ one em, ×377 dominant). The parser must **not** infer writing direction from visual appearance, and must not stamp `jtd-vertical` on appearance alone; orientation is taken from the PaperMark orientation groups (§2.5) / RFC 0007.
* **Rules are open-ended on the advance exit side (owner-confirmed)**: because a rule is not a box, its free end may remain open — at the bottom edge in horizontal text, at the left edge in vertical text. Both cases are one law: **the open end sits at the advance-direction exit** (the down-side horizontally, the left-side vertically, i.e. the tail of the forward axis). In the BOX grammar this corresponds to simply omitting the close-row declaration (`0x15`); closed contours require the explicit close.

### 2.4 Span Axis Resolution (Confirmed)

* Span coordinates `b0`/`b1` in span headers are coordinates on the **advance axis** (the text flow axis, orientation-relative), **not** the paper-width axis.
* A rule drawn across the flow (e.g. a horizontal rule in vertical text) is encoded as a wall at its crossing coordinate on the advance axis, **reaffirmed for every advance unit it crosses**; reaffirm count = crossing count (verified on controlled crossing-rule sample: 36 row headers `w4=0x008f`, span split `[0,40]→[42,160]` every row, wall tag `0x0028`=40, rendered rule position matching 41/160 of the text area within sub-millimetre margin fit).
* **Gap magnitude encodes the document-specific text behavior around the crossing zone** (a 1-unit wall in a text-free pure-rule document; a wide avoidance blank where text avoids the rule; and, as observed in reference samples and demonstrated by controlled crossing-rule samples, text populates both spans above and below the crossing zone, appearing as crossing spans). Gap magnitude is never the physical rule length.
* **Flow-grid events are text-independent**: a single rule drawn on an otherwise empty page still emits the full reaffirm/WALL sequence (verified on text-free crossing-rule sample: zero body text, reaffirm x 36 with footer only in `/Header`).
* **Event counting protocol**: raw word scans over `/DocumentText` must exclude the `SsmgV.01` stream header (leading 16 words) and embedded stride-2 enumeration tables, whose values (`0x000a`/`0x000c`/`0x000e`) otherwise masquerade as WRAP/BREAK/WALL events. The header word at offset 9 varies across sample revisions (13 → 14 when text blocks were added) — registered as an RFC 0003 decoded candidate (segment/region count field).

### 2.5 PaperMark Orientation Groups (verified across six controlled samples)

`/PaperMark` begins with `[0][N][0][0x000c][0][N-1]` followed by N groups of the shape `(0x0000, idx, 0x0001, orient)`:

| sample | writing (owner-confirmed) | orient values by idx |
| :--- | :--- | :--- |
| Controlled Sample (Horizontal) | horizontal | idx0=`0x10`, rest `0x0000` |
| Controlled Sample (Long Rule, Horizontal) | horizontal | idx0=`0x10`, rest `0x0000` |
| Reference Sample (Horizontal with fake-vertical wrap) | horizontal (fake-vertical appearance) | idx0=`0x10`, rest `0x0000` |
| Controlled Sample (Vertical) | vertical | idx0..idx2=`0x10`, rest `0x0000` |
| Controlled Sample (Vertical Crossing Rule) | vertical | idx0..idx2=`0x10`, rest `0x0000` |
| Controlled Sample (Vertical Crossing Rule with Text) | vertical | idx0..idx2=`0x10`, rest `0x0000` |

* **Discriminator (verified 6/6)**: any group with idx ≥ 1 carrying `orient=0x10` ⟺ vertical writing.
* idx0 carries `0x10` in every sample including horizontal ones — the target of idx0 remains unknown (reserved; RFC 0007 decode).
* Note on vocabulary: what this document calls a "cell" is, in Ichitaro terms, the **enclosed space between crossing rules** (e.g. two vertical × two horizontal); Ichitaro has no cell object (owner-confirmed).

---

## 3. Table State Machine (v2 Specification)

The parser detects and normalizes table structures through the following state machine:

### 3.1 Column Count Arithmetic from Line Headers

* Payload word $w_5$ in line header `w4=0x008f` strictly satisfies:
  $$w_5 = 4 \times (n - 1) + 3$$
  where $n$ is the number of physical spans in that row.
* This allows the parser to determine the expected column count $n$ deterministically at the start of each row ($w_5=7 \Rightarrow n=2$, $w_5=11 \Rightarrow n=3$, $w_5=15 \Rightarrow n=4$).

### 3.2 Span Coalescence Rules

* **Background**: When text wraps across multiple lines within a single column span, Ichitaro emits a `0x0030` (Span Reaffirm) record with identical coordinates $[b_0, b_1]$ for each display line (equivalent to Word `\intbl` lines).
* **Rule**: Consecutive `0x0030` records sharing identical coordinates $[b_0, b_1]$ within the same line or adjacent wrap cycle **must be coalesced into a single physical cell (`<td>`)**.
* **Effect**: Prevents spurious column inflation caused by soft wraps and multi-line cell entries.
* **Regression anchor (ground-truth HTML reference sample)**: Span Reaffirm 844 → physical cells 128 = 6.59×; row advances 59 → logical rows (`<tr>`) 9 = 6.56×. The two constants coincide ⇒ coalescence compression factor = display rows per cell (≈6.5 for this document). Structural regression compares only the tr/td/colspan/rowspan skeleton against the registered ground-truth HTML (content non-disclosed).

### 3.3 Boundary Strips and Gaps

* Adjacent spans typically have a gap ($b_0' - b_1$) of $2$ (or $4$) units, representing the stroke footprint of the rule itself.
* Spans with negligible widths ($b_1 - b_0 \le 4$ units) are treated as rule margins and excluded from content cells.

### 3.4 Definitive Row Advance

* **Rule**: The arrival of `0x000e` **deterministically closes the active row (`<tr>`)**. No `0x000e` occurs during transitions between cells within the same row; cell progression is signaled solely by $b_0$ coordinate shifts in `0x0030`.

### 3.5 Table Open Detection

Table opening is triggered when either of the following conditions is met:

1. **Stable Grid Repetition (Primary Signal)**:
   The coalesced physical span count satisfies $n \ge 2$, and an identical sequence of left coordinates (`lefts=[...]`) recurs across consecutive lines ("Stable Grid").
2. **Table Line Header (Secondary Signal)**:
   A `0x001c` (class `0x0010`, `w4=0x008f`) column specification header is encountered.

#### False Positive Guards
* Single-column paragraphs ($n=1$) never open a table.
* Isolated `0x000e` delimiters without corresponding `0x0030` spans never open a table.
* Non-table decorative frames (e.g., dashed sticker boxes) collapse below the multi-span threshold and are excluded.

### 3.6 Table Normalization (Padding)

* If row cell counts vary across lines, empty `<td/>` elements are padded at missing positions (or trailing positions) to match the maximum column width, ensuring valid GitHub Flavored Markdown (GFM) pipe table rendering.

### 3.7 Table Close Detection

The current `<tr>` and `<table>` are closed immediately upon any of the following events:

1. Encountering class `0x0020` (Flow Terminate).
2. Encountering a standard single-column paragraph header (class `0x0010`).
3. Encountering a text run whose coordinates deviate from the active stable grid.
4. Reaching the end of the text stream (EOS).

### 3.8 Rules Never Span a Page (owner-confirmed)

* An Ichitaro rule is drawn **within a single page**; it never physically continues onto the next page. Because a rule is an open-ended stroke (not a box), its free end may stay open — at the bottom edge in horizontal text, at the left edge in vertical text (§2.3).
* A "table that appears to span pages" is an **optical continuation**: page N draws rules ending open at the advance exit, and page N+1 draws a fresh set of rules whose open end faces the incoming side. The grids on the two pages are independent strokes that merely happen to align.
* **State-machine corollary (new close signal)**: every page boundary is a mandatory table close. A stable grid must not be carried across a page boundary as one `<table>`; instead each page opens its own `<table>`. Consecutive pages may be linked by a consumer-side hint (`data-table-run="k"`), but the DOM tables are per page.
* This resolves multi-form bundles (e.g. 9-page repeating grid): the `lefts=[4,16,24,…]` grid recurring across 9 pages is nine independent redraws of the same form, **not** one cross-page table. Each "様式第ｎ号" page closes its own table.
* It also reframes the single-table reference sample: since ruled lines never span pages, the earlier "cross-page tbody" projection is not required; each page is a self-contained table.
* **Consequence for detection (registered for RFC 0003/0007)**: page-boundary signal in `/DocumentText` must come from coordinate restart / stream-header segment count (offset 9 word, which increments as content blocks are added) and PageMark — not from a dedicated in-flow page-break code (standalone `0x000c` was shown to decompose into LEN words / stride-2 enumeration tables; §2.1). Locating the authoritative page-break trigger is the decode target.

---

## 4. Rule Visual Style Encoding (In-line Header Structure)

Visual rule attributes (thickness, color, stroke pattern, in-line vs. inter-line) are encoded directly within **the main line headers (`0x001c` class `0x0010`)**, rather than requiring secondary style streams:

### 4.1 Flow-Partitioning vs. Inter-Line Rules

* **`payload[4] == 0x14` (In-line Horizontal Rule)**: Partitions text flow horizontally; the rule line itself corresponds directly to the coordinate gap between spans.
* **`payload[4] == 0x08` (Inter-line Horizontal Rule)**: Does not partition flow; preserves a full-width single span ($[0, 160]$).
* **BOX Grammar**: Outer rule boxes are framed in three phases: **Open Row (`0x16`) $\rightarrow$ Middle Rows (`0x13` $\times N$) $\rightarrow$ Close Row (`0x15`)**. Wall tag values in the middle rows mark vertical boundary coordinates.
* **Crossing Rule Ramp**: A rule spanning multiple advance units declares **first unit (`0x12`) → middle units (`0x13` × N) → last unit (`0x11`)** in the style word, in the same grammar family as BOX open/middle/close (`0x16`/`0x13`/`0x15`).

### 4.2 Style Tail Words and Wall Coordinates

* **Vertical Wall Tags**: The tag word in column specification sub-entries (e.g., `0x4e = 78`) specifies the exact coordinate $x$ of the vertical wall (e.g., $b_1=78 \mid b_0=80$).
* **Line Header Tail Word**: Defines rendering attributes:
  * `0x22`: Thin black rule
  * `0x1c`: Thick black rule
  * `0x06`: Cyan dashed rule
  * `0x4f`: Thin vertical black rule
  * `0x00`: Standalone rule (no neighboring box context)

---

## 5. Output Projection Contracts (XHTML & Markdown)

Emitted SAX events must project cleanly to both XHTML and Markdown via Apache Tika 4.0.0 ContentHandlers.

### 5.1 Endpoint & Handler Contracts

| Target Format | Tika 4 Endpoint | Parser Responsibility | Serializer Behavior |
| :--- | :--- | :--- | :--- |
| **XHTML** | `PUT /tika/xml` | Emit complete XHTML elements (`<table>`, `<tr>`, `<td>`, `<tbody>`). | Retains XML attributes (`class`, `data-rule`, `rowspan`, `colspan`). |
| **Markdown** | `PUT /tika` (Default) | Emits standard XHTML table events. | `ToMarkdownContentHandler` (commonmark-ext-gfm-tables) converts to GFM pipe tables. |
| **Plain Text** | `PUT /tika/text` | Emits standard XHTML table events. | Strips markup tags and concatenates text nodes with newlines. |

### 5.2 GFM Pipe Table Constraints & Fallbacks

Tika 4.0.0's `ToMarkdownContentHandler` relies on `commonmark-ext-gfm-tables 0.30.0` with the following constraints:

1. **Header Row Requirement**: The first `<tr>` is automatically treated as `TableHead` (`|---|---|`), and subsequent rows form the `TableBody`.
2. **No Colspan / Rowspan Support**: GFM does not support merged cells; span attributes are ignored and cells are flattened.
3. **Table Depth Limit**: Nested tables are only rendered at `tableDepth == 1`; inner tables are flattened. The parser should encapsulate nested tables within `<div class="nested-table">` where necessary.

### 5.3 Writing Direction (Vertical Text)

* Tables in vertical text documents are projected in relative space and annotated with `<table class="jtd-vertical">`. Visual transposition is left to downstream consumers.
* **Consumer ground-truth example (registered)**: the owner's hand-made HTML reproduction of the reference sample (a horizontally-set document whose vertical appearance is fabricated by single-character-per-line wrapping; owner-confirmed) is a single table in horizontal layout — 9 `<tr>`, 128 `<td>`, colspan 54, rowspan 17, th 65, `tbody` ×1, with no `dir`/`writing-mode` declaration. It demonstrates the intended split of responsibility: the parser emits stream-structure faithfully and takes orientation only from PaperMark (never from appearance); the consumer handles presentation. GFM flattening drops all 71 span attributes on the Markdown side, as predicted by §5.2.

---

## 6. Scope Boundary: Distinct Nature from LayoutBoxes

This specification strictly governs tables formed by rules and is **fundamentally distinct from LayoutBoxes**:

1. **Stream and Lifecycle Separation**:
   * Rule-based tables are parsed directly as inline flows within `/DocumentText`.
   * LayoutBoxes reside in an independent stream (`/LayoutBoxText`), whose document-level positioning and ordering are governed by RFC 0008 and separate box extraction specifications.
2. **Recursive Application to Box Text**:
   * Because text within a LayoutBox (`/LayoutBoxText`) forms a mini-`/DocumentText` stream, when rules are drawn inside a box, the table state machine defined herein (coalesce and stable grid detection) is applied recursively to reconstruct tables within that box.

---

## 7. Under Investigation & Future Extensions

The following items are tracked for future extensions (Phase 4+):

1. **Multi-form Bundle Table Splitting**:
   Automating the split of continuous form bundles into distinct `<table>` blocks based on page breaks or heading markers.
2. **Horizontal Rule Events (`w4=0x002a`)**:
   Decoding Y-coordinate blocks to provide definitive horizontal row grid boundaries.
3. **Full-width `[0,0]` Zero-width Spans**:
   Establishing `<hr/>` projection rules for standalone inter-line rules.
4. **True Page Break Enumeration**:
   Standalone `0x000c` observed so far decomposes into cell LEN words, embedded table-structure words, and stride-2 enumeration tables (integers 1..N). Enumerate corpora occurrences outside these classes and decode the stride-2 enumeration tables and the `/Header` page-number field placeholder (`0x003f`) (RFC 0003 / RFC 0009 addenda).
5. **PaperMark Orientation Groups**:
   Verified 6/6 across the controlled samples (§2.5): orient=`0x10` at idx ≥ 1 ⟺ vertical writing; idx0=`0x10` appears in all samples including horizontal — target unknown, reserved. Formal decode into RFC 0007.
