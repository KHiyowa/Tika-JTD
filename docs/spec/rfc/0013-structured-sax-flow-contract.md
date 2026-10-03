# RFC 0013: Rule Flow Reconstruction Model and Table SAX Projection Specification (HTML Table / Markdown Contract)

Status: accepted (Phase 0–2 implemented in v0.3.0)

Observed / Established: 2026-09-28
Updated: 2026-09-30 (v0.3.0 implementation established: empty-strip filtering, column slot union/colspan approximation, multi-sheet/layout-box encapsulation, and salvage fragmentation)
Updated: 2026-10-01 (§20 binary annex registered: jsreadermi reference-reader border/decoration maps; evidence levels [L1]/[L2] separated)
Updated: 2026-10-01 (§20.6/§20.7 stream binding and on-disk field order: tagged TLV + presence-bitmap encoding, fixed-stride hypothesis eliminated)
Updated: 2026-10-02 (§20.7 re-scoped: grammar = [L1-Sun-reader] observed behavior; native attribution [L2-version-confounded] — jsreadermi targets the Ichitaro 8 generation while the probe corpus is Ichitaro 2026 authored; hypotheses H1 spool-layer and H2 older-native-dialect retained)
Updated: 2026-10-02 (§20.8 registered as independent lineage [corpus-2026]: known-edge probes → DocumentText byte diffs; chains [Sun-Ichitaro8] and [corpus-2026] isolated until adjudication; 0x1c/0x22 phrasing softened to not-reproduced/open-attribution)
Updated: 2026-10-02（§20.9 [corpus-legacy] 登記: Lite 2probe は magic 不在（H2 は真・一太郎8/9 に縮小、H1 優勢・未証明）、TextLayoutStyle ストリーム自体が不在、レコード台帳安定（0x380/0x588 は stamp 窓）、P1 double-save / P2 単一ファイル内二矩形を决定 probe とする）
Updated: 2026-10-02 (§20.10 double-save calibration: save counters +0x14/+0x20, stamp families, ledger re-encoding confirmed. Raw byte fingerprinting ruled save-unreproducible; fingerprint claims retracted, binding moved to parser walk; §20.8 byte-level notes annotated)

Japanese version: [0013-structured-sax-flow-contract.ja.md](0013-structured-sax-flow-contract.ja.md)

## Abstract

In Ichitaro documents (JTD / JTT), **"Rules (Keisen)"** drawn on the canvas are neither hierarchical table cells (Box Model: `Document → Table → Row → Cell`) like Microsoft Word, nor standalone graphical text boxes ("LayoutBoxes").

In Ichitaro, **"Rules" and "Boxes (LayoutBoxes / ObjectBoxes)" are fundamentally distinct concepts**:
* **Boxes (LayoutBoxes / ObjectBoxes)**: Standalone rectangular containers anchored on paper coordinates, managed strictly in separate streams (`/LayoutBoxText`, `/LayoutBox`, `/Frame`, or OLE2 substorages) isolated from the main document body.
* **Rules (Keisen)**: Layout mechanisms drawn directly within the main text stream (`/DocumentText`). Drawing a vertical rule erects an imperative boundary wall at a specified coordinate within the line grid, partitioning the text flow into distinct spans that wrap independently.

Ichitaro possesses no native container object representing a "Table" or a "Cell". Instead, a visual "table" emerges purely when vertical rule walls recur at identical coordinates across consecutive lines.

This specification standardizes the **flow reconstruction model, table state machine, and XHTML / Markdown output contracts** required to reverse-engineer and synthesize higher-order DOM structures (`<table>`, `<tr>`, `<td>`) for Apache Tika 4.0.0 from **text streams partitioned by rules**.

As of v0.3.0, the flow event vocabulary (`RuleFlowParser`), plain text invariants (substantive text completeness), span coalesce rules, **definitive row boundary detection via `0x000e` (Row Advance)**, **exact span count arithmetic ($w_5 = 4(n-1)+3$)**, **column slot union and `colspan` approximation (`RuleFlowAssembler`)**, multi-sheet and layout-box encapsulation, and orientation invariance are **implemented and accepted**. Multi-form bundle splitting at page boundaries, `rowspan` approximation, and fine-grained style codes remain tracked as future extensions (Phase 3+).

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

* **Substantive Text Completeness Invariant**: The character sequence emitted by the parser and extracted via the `-t` fast path must preserve all substantive text (CJK, Japanese, alphanumeric, punctuation).
  - Note: Due to formatting newlines and indentations introduced by XHTML serializers (`ToXMLContentHandler` / `ToMarkdownContentHandler`), flattened `-t` output transitions from byte-for-byte identity to whitespace-normalized equivalence (zero missing CJK/wide characters verified across regression suites).
* **Mixed-Type (P1) Prologue Preservation**: When text precedes the first marker in a TextV.01 span, it is preserved as the leading `Text` event without truncation.
* **Order Preservation**: Text fragmented across spans must be concatenated in natural stream traversal order (physical line progression and span reading order), preventing omissions or duplication.
* **Elimination of Binary Glyph Noise**: Binary control-range residues (`0x00`–`0x1f`, `0x001c`) and Latin-1 noise glyphs (`´`, `¨`, `¤`) leaked by lax run-start rules in v0.2.x are purged from structured output.
* **Autonumber Prefix Restoration**: Numbered list and bullet prefixes are reconstructed into the main text stream under the same contract as plain-text extraction (`paragraphHeaderPrefix`).

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
* **Empty Boundary Strip Exclusion**: Spans with negligible widths ($b_1 - b_0 \le 4$ units) **that contain no text (are empty)** are treated as decorative boundary strips and excluded from content cells. Narrow cells carrying text (e.g., 4-unit single-character form fields) are strictly preserved as content cells.

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

### 3.6 Column Slot Union Derivation and `colspan` Approximation

The table state machine (`RuleFlowAssembler`) buffers rows from table open until close, unifying geometry across the entire table before emitting SAX events:

1. **Column Slot Unification**:
   Computes the sorted union of all left coordinates ($b_0$) across all rows in the table to establish a definitive grid of column slots.
2. **Missing Slot Padding**:
   Automatically pads missing slots in each row with empty `<td/>` elements, guaranteeing structural regularity for GitHub Flavored Markdown (GFM) pipe tables.
3. **`colspan` Approximation**:
   When a cell's right coordinate $b_1$ spans across multiple subsequent column slots, assigns `colspan="k"` where $k$ is the number of covered slots. This attribute is preserved in XHTML output and gracefully flattened in Markdown.
4. **`rowspan` Considerations**:
   Multi-row cell merging (`rowspan`) remains deferred to future iterations (Phase 4+), prioritizing deterministic row boundary splits via `0x000e`.

### 3.6a `rowspan` Slot-Drop Approximation (Phase 3, 2026-09-30)

`rowspan` is emitted on a content cell (non-blank anchor) whose covered slot set $S$ is **fully dropped** (not covered by any cell) in the immediately following consecutive rows: `rowspan = 1 + k` where $k$ is the number of consecutive fully-dropped rows; a partial drop (some slot of $S$ still covered) terminates the extension; covered rows emit **no** cell (HTML coverage semantics). Blank anchors never anchor. GFM flattening drops all span attributes on the Markdown side (§5.2 asymmetry, unchanged). The cross-WALL reaffirm **row folding** (collapsing display-row advances into logical rows, §9.13 convergence constant ≈6.5) remains unimplemented: the corpus quantification (1,137 identical-grid runs; 526 with `w4=0x008f` headers vs 499 paragraph-header-only, WRAP densities overlapping 0.00–1.33) confirms no constant is admissible until the advance-axis polysemy (§9.10) is decoded.

### 3.7 Table Close Detection (v2 — Phase 3 revision, 2026-09-30)

The initial v1 rule "a paragraph header (class `0x0010`) closes the table immediately" produced severe fragmentation on ruled form documents: the observed reference sample stood a paragraph header on **every** WALL-bounded display row inside a single ruled block, splitting one physical table into eighteen `<table>` elements (see report §12.2/§12.3, factor 2). This close trigger is therefore **rescinded** and replaced by the suspension model:

An open `<table>` is closed only upon:

1. Encountering class `0x0020` (Flow Terminate).
2. Encountering a **span-less text run inside the table** — text that does not belong to any declared span is outside the grid, and the grid ends there. The paragraph is emitted **after** `</table>` (buffered flush ordering).
3. Reaching the end of the text stream (EOS).

Everything else **suspends but does not close**:

* A paragraph header (`0x0010`, any `w4`) inside an open table does not close it.
* A full-width single span inside an open table **with blank content** is treated as a separator rule (§9.11 standalone-rule lineage): it emits **no row** and keeps the table open.
* A full-width single span inside an open table **carrying text** (the owner-confirmed case of titles riding across rules, §9.12) becomes a full-colspan row inside the table — never a `<p>`.
* Stable-grid detection (§3.5) still requires the pre-open pending buffer to be preserved across paragraph headers (§3.5 primary signal correctness depends on it; regression-tested).

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
4. **In-cell Line Break Flattening (Phase 3, 2026-09-30 — primary Markdown-corruption fix)**: The SAX pipeline is a single pipe; the serializer renders `<br>` inside `<td>` as a hard break (`  \n`), which terminates the GFM table row mid-cell and sprays the remaining columns as loose text (observed: 179 reference-sample cell breaks producing ≈43 fragment lines, report §12.3 factor 1). Contract: the parser **never emits `<br>` inside a table cell** — a stream wrap (`0x000a`) inside a cell projects to a single ASCII space, consecutive wraps collapse to one, and wraps without adjacent text (blank span-advance markers, §9.13) are discarded. Paragraph-level `<br/>` stays (block-level hard breaks are harmless in Markdown).
5. **Short Rows Are NOT auto-padded (measured)**: commonmark emits missing trailing cells of short rows as-is (`|四|`), keeping one delimiter per table block even when row widths vary; consecutive independent tables arise only from `<p>` interruptions, never from width changes. Therefore missing-slot padding is emitted by the **parser** as one empty `<td>` per missing slot (no merged wide padding), while span-covered cells stay omitted (valid GFM trailing-blank semantics on the Markdown side).

### 5.3 Writing Direction (Vertical Text)

* Tables in vertical text documents are projected in relative space and annotated with `<table class="jtd-vertical">`. Visual transposition is left to downstream consumers.
* **Consumer ground-truth example (registered)**: the owner's hand-made HTML reproduction of the reference sample (a horizontally-set document whose vertical appearance is fabricated by single-character-per-line wrapping; owner-confirmed) is a single table in horizontal layout — 9 `<tr>`, 128 `<td>`, colspan 54, rowspan 17, th 65, `tbody` ×1, with no `dir`/`writing-mode` declaration. It demonstrates the intended split of responsibility: the parser emits stream-structure faithfully and takes orientation only from PaperMark (never from appearance); the consumer handles presentation. GFM flattening drops all 71 span attributes on the Markdown side, as predicted by §5.2.

### 5.4 Document Structure, Multi-Sheet, and Box Encapsulation Contracts (v0.3.0 Established)

In integration with Apache Tika 4's SAX output pipeline (`XHTMLContentHandler`), the following encapsulation contracts are established:

1. **Multi-Sheet Document Encapsulation**:
   - Each sheet is delimited within a `<div class="sheet">` element prefixed with `<h2>{Sheet Name}</h2>`.
   - Sheet body text, reconstructed tables, and sheet-specific footnotes (`<p>{Footnote}</p>`) are enclosed within this container.
   - Ordering contracts across sheets and within sheets remain invariant from v0.2.2.
2. **LayoutBox (`/LayoutBoxText`) Encapsulation**:
   - LayoutBoxes serving as text boxes are emitted at the document end preceded by the compatibility text node `※枠内テキスト` and enclosed within a `<div class="layout-box">` element.
   - Individual blocks within the box are structured as separate `<p>` paragraphs to maintain plain-text compatibility (`-t`).
3. **Elimination of Redundant `<body>` Elements**:
   - `XHTMLContentHandler` automatically manages root `<body>` start and end tags coordinated with `startDocument()` and `endDocument()`. Explicit manual `<body>` tags are eliminated from the parser to ensure clean, valid XHTML.
4. **Salvage De-fragmentation for Corrupted Containers**:
   - When raw streams extracted from corrupted CFB containers contain multiple segments concatenated by `[0x00, 0x00]`, the stream is partitioned along word-aligned magic boundaries, allowing each segment to undergo independent flow parsing.
5. **Deterministic Fallback Gate**:
   - If an unexpected error occurs during rule-flow assembly or sheet reading, the parser safely falls back to single-node text emission, guaranteeing that no substantive character content is lost.

---

## 6. Scope Boundary: Distinct Nature from LayoutBoxes and Structural Connection

This specification strictly governs tables formed by rules and is **fundamentally distinct from LayoutBoxes**:

1. **Stream and Lifecycle Separation**:
   * Rule-based tables are parsed directly as inline flows within `/DocumentText`.
   * LayoutBoxes reside in an independent stream (`/LayoutBoxText`), whose document-level positioning and ordering are governed by RFC 0008 and encapsulated at the end within `<div class="layout-box">` in v0.3.0.
2. **Recursive Application to Box Text (Future Work)**:
   * Because text within a LayoutBox (`/LayoutBoxText`) forms a mini-`/DocumentText` stream, when rules are drawn inside a box, the table state machine defined herein (coalesce and stable grid detection) can be applied recursively to reconstruct tables within that box.

---

## 7. Implementation Status & Future Work

### 7.1 Implemented & Accepted in v0.3.0 (incl. Phase 3 docker-roundtrip items, 2026-09-30)
* **Flow Event Scanner (`RuleFlowParser`)**: Strict event recognition validated by RFC 0009 footer `(len, 0x0000, class, 0x001f)`, mixed-type (P1) prologue preservation, binary glyph purging, and autonumber prefix restoration.
* **Definitive Row Advance**: `0x000e` serving as the authoritative `<tr>` row boundary.
* **Span Coalescence**: Soft wrap display lines within identical coordinates unified into single `<td>` cells.
* **Empty Boundary Strip Exclusion**: Strips with width $\le 4$ units excluded only when empty.
* **Table State Machine (`RuleFlowAssembler`)**: Table open detection via `w4=0x008f` line headers or stable grid recurrence; whole-table row buffering; column slot union derivation; missing slot padding; and `colspan` approximation.
* **Suspension Model for Table Close (§3.7 v2, T3-2)**: Paragraph headers no longer close tables; reference-sample fragmentation 18 $\rightarrow$ 1 confirmed. Full-width blank spans act as separator rules without rows; full-width text spans become full-colspan rows.
* **In-cell Break Flattening (§5.2-4, T3-1)**: No `<br>` inside `<td>` (ASCII space projection, blank-wrap discard, consecutive collapse); paragraph `<br/>` preserved. Reference sample: fragments ≈43 $\rightarrow$ 0, zero broken pipe rows.
* **`rowspan` Slot-Drop Approximation (§3.6a, T3-3 partial)**: Non-blank anchors extended across consecutive fully-dropped rows, colspan-compatible; reference sample emits rowspan attributes (17 target per §9.13; v1 emits the slot-drop subset pending polysemy decode).
* **Per-Slot Padding**: Missing slots padded as individual empty cells (no merged wide padding), guarding GFM pipe uniformity for simple drops.
* **SAX / Markdown Dual Contract (`FlowSaxEmitter`)**: Emission of valid XHTML elements (`<table>`, `<tr>`, `<td>`), with automated conversion to GFM pipe tables by Tika's `ToMarkdownContentHandler`.
* **Structural Encapsulation**: Containers for `<div class="sheet">` and `<div class="layout-box">`, salvage multi-fragment splitting, and removal of duplicate `<body>` tags.
* **Standing Opt-In Structural Rail (§12.5)**: Ground-truth HTML skeleton fingerprint matching (tr/td/colspan/rowspan numerals only; content undisclosed), enabled by `-Pflowstruct.rail`.

### 7.2 Tracked for Future Extensions (Phase 3+ / Phase 4)
1. **Cross-WALL Row Folding (advance-axis polysemy)**: Collapsing display-row WALL advances into logical rows (reference tr=9 vs WALL=58, §9.13 constant ≈6.5). **Partially resolved by §13.2**: the tall-cell discriminator (per-row `w4=0x008f` RowHeader + in-cell trailing WRAP + pre-filter identical span signature) folds explicit calendar continuations while header-less shared-header runs keep independent `<tr>` (§3.4). Residual polysemy: identical-grid runs still occur across the corpus (526 row-header-declared vs 499 paragraph-only, §9.10) and the **slot-drop `rowspan` approximation (§3.6a) remains the accepted interim rule** where the §13.2 signal is absent.
2. **Page-Boundary Table Segmentation**: Leveraging authoritative page boundary triggers (stream header offset 9 word or PageMark) to split continuous grids across pages (§3.8 corollary; prerequisite for per-page table discipline).
3. **Sheet-Scope LayoutBox Extraction & Recursive Parsing**: Extracting `DOCS_XXXX/LayoutBoxText` and recursively structuring intra-box ruled tables.
4. **Inline Structural Enhancements (T3-4 inherited)**: Ruby text `<ruby><rb><rt>` (§5.4, coverage-90% wall), heading mapping from `w7` paragraph style (RFC 0009 addendum table), list numbering `<ol>/<li>` via `DocumentTextNumbering`, and the empty-body box-promotion decision (§10.3-6).
5. **Writing Direction Metadata**: Adding `<table class="jtd-vertical">` driven by PaperMark orientation groups (§2.5) — sequenced after T3-2/T3-3 so attribution targets are settled.
6. **BOX Grammar Styles & Standalone Rules**: Decoding full BOX open/middle/close grammar and ramp codes into `data-rule` attributes, and projecting full-width `[0,0]` rules as `<hr/>`.

---
* O2 (§21→§22 reclassified): oracle-only seam intent / not inferable from native structure (per-cell ±1 on the §9.13 document, 6 cells; word-6 census 844/844 = `0x00ff`, suppression rules silent; oracle keeps `br` in 30 of 128 cells and concatenates 98 under the same native signature). Strict per-cell rail `inCellBrVectorIdentityVsOracle` stays registered-red pending the §22 open question to the owner; no parser rule may be added to guess owner intent.

## 13. Tall Cells and Head-Matter Prologue (2026-09-30 observations; Phase 2 addendum)

Two contracts confirmed on real calendar (yearly-schedule) and fill-in-grid documents, registered as **restrictive clauses that do not override** §3 Table State Machine and §5 Output Projection. The existing §3.4 Definitive Row Advance and §5.4 / §12.4 T3-1 in-cell break flattening remain in full force in every case not enumerated below.

### 13.1 Prologue restoration for TextV.01-less streams (loss prevention)

* Some `/DocumentText` streams have no `TextV.01` segment name right after the `SsmgV.01` header; instead the raw UTF-16BE body (title lines etc.) runs from header word 10 up to the first RFC 0009 marker (`0x001c` / `0x001d` / `0x001f`).
* For such streams that region is restored as the stream's leading `FlowEvent.Text` (before any `<table>`), eliminating the wholesale loss of the document's off-table title line.
* Regression guards:
  * `TextV.01`-bearing documents (mixed-type P1) stay owned by `rawPrologueText`; this clause does not fire (exclusive).
  * No prologue is produced when the scanned region holds no visible text word other than blanks (same visible-word test as §3: CJK / fullwidth / ASCII printable).
  * If even one out-of-rule word (non-visible, `0xFE00`–`0xFE0F`) appears in the region, the prologue is rejected outright (never mix mis-decoded garbage).
  * A marker below word 10 (legacy fixtures whose structural records begin at the head) does not fire.
  * CR / LF terminate the prologue body (the wrap itself is left to the main scanner's `FlowEvent.Wrap`, avoiding double emission).

### 13.2 Tall-cell logical-row merge and `FlowPart.LineBreak`

In calendar rows a single logically **tall cell** is folded across several text rows separated by `WALL(0x000e)`. The merge into one logical `<tr>` is fixed here.

* **Merge condition** (only for physical row pairs $A$(preceding) / $B$(following) satisfying all):
  1. At least one content cell of $A$ (after boundary-strip exclusion) terminates its raw parts with an in-cell WRAP (`FlowPart.Break` from `FlowEvent.Wrap`) — the signal that $A$ folds into the next text row.
  2. $B$'s raw declared span sequence (all cells, before boundary-strip filtering) is the identical signature (ordered $(b_0,b_1)$ list) of $A$'s.
  3. Both $A$ and $B$ declare **their own RowHeader** (`0x001c/0x0010` `w4=0x008f`). Header-less WALL rows chained under a shared header (§12.3) keep their independent §3.4 `<tr>` and are not merged.
  4. Runs of 3+ rows stay open and concatenate while each seam satisfies the above; after a merge, the continuation flag is refreshed from $B$'s fold signal.
* **Projection**: a merged seam projects to `FlowPart.LineBreak`, emitting `<br/>` in XHTML. In-cell `FlowPart.Break` flattening to a single ASCII space (§12.4 T3-1 / §5.4) is unchanged even at seams (`FlowPart.LineBreak` and `FlowPart.Break` are distinct vocabularies).
  * e.g. `|１<br/>月|新入生テスト|開校記念日 <br/>魚沼実踏①|...|` (one logical row).
* **Blank continuation**: when $B$'s matching cell is blank no seam is inserted and $A$'s parts are kept as-is (blank/blank stays `<td></td>`).
* **Boundary strips**: merge signature and merging are computed on all pre-filter cells; the $b_1-b_0 \le 4$ blank-strip exclusion is applied when the logical row flushes into `tableRows`. On real calendars a strip cell carries `··`-like blank text on alternating rows; filtering before signature comparison would give the same logical row two different signatures and break the merge (rationale of §13.2).
* **`rowspan` approximation (§3.6 / §12.4 T3-3)**: because merging reduces logical rows below physical rows, the §3.6 column-slot union and `rowspan` derivation are computed over the **merged logical rows**.
* **Markdown (§5.2 / §5.4)**: a merged cell's `FlowPart.LineBreak` is `<br/>` in XHTML but converts to a hard break (`  \n`) when handed to Tika's `ToMarkdownContentHandler`, fragmenting the GFM row by the same primary cause as T3-1. The parser does not add `<br/>` here; fragmentation avoidance stays delegated to the §5.2 fallback and §7.2 tracking item.

### 13.3 Tracing & acceptance

* An opt-in rail (`-Ptallrow.rail`) fixes §13.1 head-paragraph restoration and §13.2 tall merge (`br-in-td > 0`) as skeleton numerals via document skeleton fingerprint matching (`tr` count, in-cell `<br/>`, leading paragraph).
* The `-t` delta for these documents is verified as an order-preserving superset of the prior content under full-whitespace-stripped normalization (§2.2 plain-text invariant) with zero loss of non-visible glyphs, by HEAD comparison.

## 14. Folding and Continuation of Ruled-Grid Structure (2026-09-30 observations; addendum finalized)

This section registers five contracts that restrict and reinforce the §3 row/cell state machine, §5 output projection, and §13.2 tall-cell merge. They preserve the column-slot union, text retention, and definitive WALL boundaries.

### 14.1 Restricting false inline rejection (R1)

* In RFC 0009 record validation, reject body `0x001d` only when it is immediately followed by `0x001e` (inline-interval close). A bare `0x001d` may equal a valid coordinate word, such as the column-region word w5.
* The observed inter-row separator header form (len=`0x0029`, w5=`0x001d`) is accepted under this rule. The former guard falsely rejected it; after rejection, the exposed len word `0x0029` (`)`) leaked into the body and split the table at physical-row boundaries, violating the §9.8 preservation invariant.
* The false-positive guard against sequential control-word runs `0x001d`–`0x002a` remains in force.

### 14.2 Narrowing tall-row merging (R2)

* In addition to the same-span-signature and row-header conditions in §13.2, do not merge when the following physical row declares a same-span new text in a closed (nonblank and not WRAP-terminated) text cell of the preceding logical row, and the following declaration has word 6=`0x0001` (complete cell). Prefer the definitive §3.4 row boundary and emit a separate `<tr>`.
* Wrap continuations declared with word 6=`0x00ff` / `0x0000`, and writes from a blank cell into a text cell, continue to merge under §13.2.

### 14.3 Folding isolated rule fragments (R3)

* When a blank span of width at most 8 is strictly contained by a wider span declared in another row, fold its contiguous fragment run into one blank cell with the covering span. An isolated rule (§4.2, terminal-word family `0x00`) may split a cell: the flow can declare fragments on both sides of the split although the logical structure is one cell.
* Folding removes false column boundaries and `colspan` introduced into the §3.6 column-slot union. Never fold a fragment carrying text; §2.2 text preservation takes precedence.

### 14.4 Downward-continuation anchor (R4)

* Cell-header word 7=`0x0002` declares an anchor whose same-span cell continues into the immediately following WALL row.
* If the nonblank cells in that following WALL row are exclusively same-span matches for anchor cells, absorb their text into the anchor cells with [FlowPart.LineBreak] (`<br/>`), rather than emitting the fragment row as an independent `<tr>`. If a nonblank cell on a different span is present, preserve both rows.

### 14.5 Rowspan folding for block columns (R5)

* A row whose first span is blank and WRAP-terminated starts a block of tall cells. Scan declarations of that span in a window up to the next block start (the same span redeclared blank+WRAP), a closed nonblank text cell, or a missing span declaration. If the window contains at least one text fragment, collect its text in the first cell, derive `colspan` from the column slots, and set `rowspan` to the window length. Join fragments with [FlowPart.LineBreak] (`<br/>`). Blank cells representing only rule boxes inside the window are folded and removed as well.
* Rowspan folding that hoists text applies only to narrow block columns (span width <= 16 units, the one-character-grid ruled label columns observed in practice); wide columns keep each row's text in place as independent boxes, with only the blank-span colspan×rowspan rule applying (safeguarding the §2.2 order-preservation law).
* A blank wide cell inside a block receives both `colspan` and `rowspan` as a single blank cell only when the same span is present on every row in the window and another row in the table declares an internal vertical-wall coordinate within it.

### 14.6 Projection and acceptance

* As in §13.2, merge seams use [FlowPart.LineBreak] and project to `<br/>` in XHTML. The §12.5 T3-1 “zero in-cell br” rail is updated to allow registered seams in this section; the re-registered value on 2026-09-30 is `br-in-td=6`.
* The Markdown fragmentation signal (pipe rows ending in two spaces) likewise tolerates fragments arising from the same registered junctions; the 2026-09-30 re-registration value is 5 (§5.2 and §7.2 tracked items).
* Synthetic contract tests are registered in `RuleFlowBlockColumnStructuringTest` (*Night on the Galactic Railroad* fixtures). Text goldens have been recaptured via `captureGolden`; a cross audit against the pre-fix parser (HEAD, 604 files) classifies as: exact 432, whitespace-normalized-equal 77, same-multiset reorderings 23 (reading-order concentration inherent to §13.2/§14 rowspan projection), structural-word un-leak removals 72 (ASCII len-echo words the old guard leaked into body text due to false inline rejection; zero loss of Japanese body characters), and no old capture 2. All order changes are anchor-row concentrations consistent with the ground-truth skeleton.

## 15. Pending Table-Head Adoption and Indented Wrap Continuation (2026-10-01 observations; addendum finalized)

This section registers three contracts that reinforce the §3.5 table-open decision, the §13.2 tall-cell merge, and the §14 R2 conflict test. They were observed and finalized by cross-checking against the PDF grid oracle (`tools/pdf_grid_oracle/`, a development harness that reconstructs cell-grid ground truth from the rule vectors of Ichitaro-printed PDFs).

### 15.1 Pending adoption at table open (refinement allowed)

* When table open is decided by the row-header reinforcement (w4=`0x008f`), adopt the pending multi-span rows (typically a header row without a row header; all rows if several) as the leading table rows if the span left-edge coordinate set equals that of the opening row **or one is a subset of the other (column refinement)**.
* Refinement is frequent on real layouts where dotted (thin) rule columns are subdivided only in body rows: the header span straddles the pre-refinement columns and projects as a `colspan` approximation. Together with §15.2, this eliminates the header-row-to-paragraph defect.
* Pending rows on crossing or unrelated grids continue to be evacuated to a paragraph, preserving the §2.2 text-retention invariant.

### 15.2 Blank divider rows must not destroy pending

* A segment with empty `content` (duplicated WALL / separator-rule-only rows) must not clear pending rows before table open. Pending is kept until connected to a subsequent table open; paragraph evacuation at stream end is guaranteed by the §3.7 EOS abandonment.
* This fixes documents whose layout places a blank divider between the head row and the first body row, where the head row had been abandoned to a paragraph.

### 15.3 Indented wrap continuation absorption (B)

* Layouts that wrap cells by WALL segmentation alone (no WRAP word `0x000a`) leave the §13.2 wrap-termination signal untriggered. On such layouts Ichitaro prefixes wrap-continuation text with a leading ideographic-space indent.
* Within an open table, for a fragment row that carries a row header and whose span signature maps onto the preceding logical row (subset or equal), absorb the fragment row into the preceding logical row joined by [FlowPart.LineBreak] (`<br/>`) when **every non-blank text cell begins with leading whitespace and its same-span preceding cell is non-blank** (at least one such cell required). Do not emit a standalone `<tr>`.
* Guard: do not absorb rows containing un-indented new text, non-blank cells on undeclared spans, or cells whose preceding side is blank (flush as a new logical row). The §14 R2 complete-cell conflict test is updated to span-keyed matching, and is unchanged in effect because equal signatures yield identical results under both matching modes.

### 15.4 Projection and acceptance

* Seams project to [FlowPart.LineBreak] → `<br/>` as in §13.2/§14.6.
* Synthetic contract tests are registered in `RuleFlowPendingHeaderRowAdoptionTest` (three adoption contracts) and `RuleFlowTallRowContinuationTest` (refinement absorption and indented-continuation absorption), using Night on the Galactic Railroad fixtures.
* Real-corpus text-golden cross audit over 604 files (before/after this addendum): 464 byte-identical, 83 identical after whitespace normalization, 57 order-only differences, **zero character loss (CJK included)**, zero exit-code flips. The reordering moves fragment text to its physically correct position, corroborated by the PDF oracle observation of indented continuation cells. vertical / keisen / jttc families show zero differences.

## 16. SPAN-Tree Grid Reconstruction of Vertical Bands (observed 2026-10-01; addendum finalized)

This addendum complements the §3.4 WALL row boundary and the §13.2 vertical continuation merge, restoring vertical-writing contiguous regions (bands, e.g. timetables) into visual-row grids. It was observed and confirmed by correlating PDF-grid-oracle horizontal-rule geometry (x-extents of intra-band rules) with byte-stream open/close cycles.

### 16.1 Band detection, buffering, termination

* A row without a row header (`w4=0x008f`) that carries at least one wrap-terminated cell (word `0x000a`) is a physical row of a vertical band. While in table context it is buffered into the band buffer instead of flushed.
* If wrap-open rows occur before the table opens (pending stage) and the stable-grid adoption (§3.5/§15) opens the table, the opening row starts a band, and any wrap-open pending rows adopted at open time move into the band as well. This removes the mis-projection leaving band declaration rows as bare `<tr>`s.
* A band terminates at a row carrying no wrap-open cell at all (blank divider rows and closed tally rows included), at a row-header row, or at table commit. A row that is blank of text but still carries wrap-open cells is vertical-flow filler (the boxes remain open downward) and does not terminate the band.

### 16.2 No ghost rows from blank dividers

* An all-blank row emits no `<tr>` within table context (flush only). It carries no text, so the §2.2 conservation law is untouched; any open logical row is finalized.

### 16.3 Visual-row boundaries (open → body → close → gap → reopen)

* Two boundary kinds advance the visual-row counter:
  - (a) Reopen boundary: a span that closed without WRAP and reappears with body text after a gap of at least one row. An immediate reopen with a zero-row gap is an in-box wrap of the same box, not a boundary.
  - (b) Subdivision boundary: new span coordinates carrying body text appear inside the extent of a closed and absent parent span (e.g. elective-slot subdivisions).
* The visible row count is max-index + 1. Runs of band declaration rows without row headers or blanks fold into a single visual row until text-only rows produce a boundary (grade-year 1 = 1 row).

### 16.4 Lane units and joins

* A span tracked through the band is a lane. While the immediately-preceding gap is zero rows, content joins the same lane unit (including immediate reopen after a close); an appearance after a gap of at least one row starts a new unit.
* Joins into a unit: digit runs prepend one half-width space; ASCII printable runs pad one half-width space on each side; single characters concatenate directly (vertical-flow glyphs); anything else (multi-character) joins with [FlowPart.LineBreak] (`<br/>`). Cells carrying no text (rule fragments, diagonal boxes) contribute nothing to joins.
* A visual row emits only the boxes that started their unit at that row, in ascending left order (blank boxes included). Tall-box rowspans are computed by the band reconstruction as the number of visual rows a unit covers and handed to buildTable as forced spans. The legacy forward scan could over-span beyond the band (e.g. into footer number rows); the forced spans keep tall depths exact within the band.

### 16.5 Projection and acceptance

* Synthetic contract tests are registered in `RuleFlowVisualGridTest` (three visual rows, tall rowspans, subdivision, tally, ghost-row prohibition, text conservation; Night on the Galactic Railroad fixtures).
* Real timetable-style document (three vertical bands, 1/3/3 visual rows): `<tr>` 57 → 9; the 7 tbody rows match the hand-tuned golden XHTML cell-for-cell in colspan/rowspan structure; full character multiset preserved. The ruled-grid archetype document shows zero output diff.

## 17. Header-ed Vertical Bands, Numeric Sub-column Headers, and Phantom Edge Columns (observed 2026-10-01; addendum finalized)

This addendum complements the §13.2 continuation test, the §15 header adoption, and the §3.6 missing-slot padding. Observed and confirmed via the PDF grid oracle (real band rows carrying a row header `w4=0x008f` on every row fold through the §13.2 ladder, a distinct path from the header-less §16 bands).

### 17.1 Living-continuation preservation (blank WRAP-open filler rows)

* The §13.2 openContinued (living) state is granted by text-bearing WRAP-open cells (legacy contract).
* A row blank of text but still carrying WRAP-open cells (vertical-flow blank filler) does not kill living: it is preserved if it was alive. Living never resurrects from non-living by a blank row.
* Living stops at any row carrying a closed (no-WRAP) text cell (the §15B indented continuation rung picks those up).
* This joins tally rows (unit counts) placed past blank filler rows into the same logical row.

### 17.2 Structural adoption of numeric sub-column headers (digitHeaderLike)

* When a row-header row opens the table, a pending header row that does not share grid left coordinates with body rows is still adopted as the header row if it consists of many (≥8) subdivided boxes and has at most 2 non-digit-text boxes (diagonal corners, "grade/time" title labels, etc.).
* Paragraph-like rows (single wide span, zero digit text) do not qualify and fall back to paragraph abandon, keeping the §2.2 conservation law.

### 17.3 Phantom edge-column removal

* At the first or last grid coordinate: when every row declares a box at that left edge, all boxes of the column are blank, and the box is thin (width ≤8), the column is a leftover phantom outside the ruled frame and is removed with its boxes (byte-level signature of a margin strip declared blank by all rows including the number header).
* Intentional blanks (fill-in boxes, diagonal cells, §3.6 padding slots) stay: they are either not declared in every row, not thin, or text-bearing (guarded by the §T3-1/§3.6 contract fixtures).
* Forced rowspans (band preSpans) and block spans (blockSpans) cellIndex values are shifted by the number of removed leading boxes to stay consistent. Removing blank columns leaves the §2.2 conservation law untouched.

### 17.4 Projection and acceptance

* Synthetic contract tests are registered in `RuleFlowHeaderedBandTest` (numeric header adoption, header-ed band folded into one row with tally joined, living preserved across blank fillers, phantom edge-column removal, conservation; Night on the Galactic Railroad fixtures).
* Real elective-timetable document: the fallen number-header row returns to the table head, each grade becomes a visual row with tall boxes joined, phantom edge columns removed; row count matches the oracle grid (5×32). The previous target documents (timetable archetype, ruled-grid archetype) keep byte-identical output.

## 19. Closed-family (bit0) word-6 text cells advance rows (observed 2026-10-01; addendum finalized)

Completes the §14 mergeConflict closure test. Observed on a guided-study archetype with a
stable repeated grid: subject-name cells carry word-6 = 0x0003 (closed, slim) and advance
with new text every row, while mark cells (word-6 = 0x0001) shift columns row by row and
pair against blanks, so the legacy exact match (word-6 == 0x0001) detected no boundary and
multiple visual rows over-merged into one logical row.

* Discriminator: standard cells (word-6 = 0x00ff) are explicitly excluded; a new text of a
  closed-family box with bit0 set (0x0001 closed, 0x0003 closed-slim, etc.) advancing onto
  the same span of an already closed (non-WRAP-open) text box is a row boundary.
* Excluding standard cells keeps the elective-timetable archetype (all word-6 = 0x00ff,
  §17) and WRAP-open label lanes untouched, preserving the legacy vertical-flow joining.
* Registered in the synthetic contract test `RuleFlowClosedTextRowBoundaryTest` (three
  rows split by subject advance on a stable grid; label joining and conservation kept).
  On the real guided-study document the over-merge resolves (visual rows 80 → 88, each
  subject on its own row). The previous archetypes (timetable, elective grid, ruled form)
  keep byte-identical output.

---

## 20. jsreadermi Reference Reader Border & Decoration Maps (observed 2026-10-01; binary annex)

This annex registers behavioral observations taken from the Sun-distributed free Ichitaro import filter (`jsreadermi.dll`, RFC 0002, hash-verified). No implementation code is carried over; only observed correspondences (codes → string values) are recorded. The purpose is to give §4's inline rule-style vocabulary quantitative anchors and to fix the cell-border attribute model required by the LayoutBox recursive-parsing work (§6, §7.2-3). Analysis was performed as scratchpad disassembly; addresses below are evidence anchors only.

**Evidence-level convention.** This is one independent implementation written by Sun for interoperability; its internals are not canonical definitions of the JTD format. Claims are therefore tagged: **[L1]** = observed binary behavior (a fact about Sun's reference reader — its RAM model and its SAX projection); **[L2]** = an inference about the JTD on-disk encoding derived from [L1], pending byte-level corpus verification. [L1] facts do not, by themselves, certify on-disk layout.

**Evidence-lineage convention (2026-10-02).** Two evidence lineages are kept separate until adjudicated: **[Sun-Ichitaro8]** (§20.1–§20.7; reader behavior observed from the filter, which per owner knowledge serves Ichitaro 8/9/10/11 — version labeling is owner-supplied context, not verified from the oxt, whose `description.xml` carries no version range) and **[corpus-2026]** (§20.8; byte-diff ground truth of probes authored in Ichitaro 2026). Joining the lineages — asserting that the §20.7 grammar exists in 2026 native bytes, or binding §20.8 records to §20.7 edge slots — is not permitted before the §20.7 adjudication experiments (Ichitaro 8/9/10-era controlled corpus; spool capture) are completed. The ~20-year native version gap between the filter's target generation and 2026 makes any un-adjudicated join unsafe.

### 20.1 Stream open roles

The filter opens input streams by name and loads each through a dedicated loader. Observed roles:

| Stream | Role (observed) |
| :--- | :--- |
| `DocumentText` | Body text; dedicated text loader (via `0x10038280`) |
| `LayoutBoxText` / `Header` / `PageLayoutStyleHeader` | Body-class streams (same loader family `0x10039150`) |
| `LayoutBox` / `Figure` / `FDMIndex` / `FDMVector` | Structural-object loader (`0x1003cde0`) |
| `FigureData` / `main_data` | Raw-data loader (`0x1003cea0` family; consistent with embedded Press-family content, cf. RFC 0011) |
| `EmbedItems` | Embedded-item enumeration (referenced from three sites) |

Input is additionally spooled to a temp file (traces of `GetTempPathA`/`GetTempFileNameA` with `fopen`/`fread`/`fclose`/`remove`). The binary's string image is ANSI/Shift-JIS; no UTF-16 vocabulary strings exist (font-name fragments only).

The loader split itself is the structural observation [L1]: Sun's reader keeps body-class streams (`DocumentText`, `LayoutBoxText`, `Header`, `PageLayoutStyleHeader`) in text-loader families while `LayoutBox`/`Figure`/`FDM*` go through a separate structural-object loader family — independent support, from a reference implementation's vantage, for §6's stance that rule-bearing text and structured LayoutBox objects are distinct in nature. This is a fact about Sun's design, not by itself a format claim [L2].

### 20.2 Cell-edge border descriptors (4 edges x 32 bits)

The reference reader's cell model carries four edge specifiers per cell (left `+0x04`, right `+0x0C`, top `+0x14`, bottom `+0x1C`; in-memory cell records use stride `0x20`) — all of this is **[L1]**, i.e. Sun's post-parse RAM layout, not yet a statement about `/LayoutBox` on-disk bytes. Each specifier is a 32-bit word whose most-significant byte is the border style code and whose low 24 bits are the RGB color. Projection rules [L1]:

* Style code `0` → `fo:border-<edge>` = `"none"`.
* Nonzero → `fo:border-<edge>` = `"<width>cm <style> #<RRGGBB>"` (bytes normalized BGR → RGB before formatting).
* Only for code `4` (double): additionally emit `style:border-line-width-<edge>` = `"0.002cm 0.088cm 0.002cm"` (outer inner outer).

Style-code table (double-checked via the branch at `0x10034930` and the jump table at `0x10034c8e`):

| Code | Width | Style |
| :--- | :--- | :--- |
| `0x01` | 0.035cm | solid |
| `0x02` | 0.088cm | solid |
| `0x03` | 0.002cm | solid |
| `0x04` | 0.092cm | double |

Cell styles are auto-named `<style:style style:name="cell%d" style:family="table-cell">` and attached via `table:style-name`. Column-width arithmetic scales a 16-bit word by `× [rec+0xC] >> 1`, consistent with the word-and-scale dual declaration in RFC 0006 §13.2.

### 20.3 Frame and decoration vocabulary

Text-box frame borders form a (line kind) × (width) grid: `single` / `double` / `triple` combined with 13 width quanta from 0.001cm to 0.17cm and dash parameters {450, 900, 1200, 1350, 1500, 1800}, formatted as CSS values inside CDATA (dispatch code band includes `0x1e`, `0x16`, `0xd3`). The decoration (underline / strike) vocabulary present in the image — `none`, `single-line`, `thick-line`, `double-line`, `small-wave`, `double-wave`, `bold-wave`, `bold-dotted`, `bold-dash`, `bold-long-dash`, `bold-dot-dash`, `bold-dot-dot-dash`, `wave`, `dotted`, `dash`, `long-dash`, `dot-dash`, `dot-dot-dash`, `double` — corroborates the richness of Ichitaro wavy underlines and feeds §7.2-4 inline structural enhancements.

### 20.4 Record-building pipeline at the generation point

The L1 evidence for where cell records come from has been located. Two builder functions (`0x1000eec0` and `0x10017268`) assemble unit records whose on-stack footprint is `0x51C` bytes (`lea edi, [ebp-0x51C]`), pack presence flags into a WORD at record offset `0x00` (bit0/bit1/bit2 gating per-part presence), place a WORD rule code at offset `0x1E`, and invoke a code-normalizer at `0x1001fdb0` **four times per record pass** — a per-edge pipeline [L1].

The normalizer resolves the WORD code through a byte table at `0x10056770` (codes `0x00`–`0x10`; a code of `0x0000`/`0xFFFF` first normalizes to `1`). The observed 17-entry alias table collapses the code space into five categories `{0, 1, 2, 3, 4}` with many-to-one aliasing (e.g. raw `0x01`→3, `0x02`→1, `0x03`→2, `0x07`→4, `0x08`→4, `0x0F`→4, `0x10`→2), showing that Sun recognized a richer raw rule-code set than the four display kinds it ultimately projected [L1]. Adjacent pointer tables at `0x100567A8` map normalized indices to decoration name strings (`single`, `double`, `dotted`, `*-wave`, …), corroborating §20.3.

Corollary [L2, sharpened]: the `/LayoutBox` (and sibling) on-disk unit record should expose (a) presence-flag bits, (b) per-edge rule codes of 16 bits raw (pre-collapse), and (c) rule color words; the `0x51C`/`0x20` strides observed in RAM are candidates, not confirmations, for file-record strides.

### 20.5 Cross-check against this RFC and corollaries

1. Against §4.2 [L1→L2 pending]: `0x22` (thin) and `0x1c` (thick) gain quantitative *reference* anchors — Sun renders thin as solid 0.035cm and thick as solid 0.088cm. These are Sun's chosen quanta, not canonical JTD values; the encodings also live at different layers (inline header words vs. object-model descriptors). Identity of code meanings awaits corpus verification against rendered output.
2. Against §6 [L1]: in Sun's RAM model the cell border attributes are four per-edge independent specifiers, and the loader families split body-class text streams from structural-object streams. The normalization of §4.2 footer codes into the same four-edge model for §7.2-3 projection is therefore a *design target informed by Sun's approach*, not a format-guaranteed shape.
3. Corpus verification task [L2]: byte-level confirmation on `/LayoutBox` cell entries — (i) four rule fields per cell (code+color), (ii) 16-bit raw codes consistent with the §20.4 alias table domain (`0x00`–`0x10`), (iii) presence-flag words at the declared record offsets. On mismatch, attribute the difference to Sun-internal normalization (RAM strides `0x20`/`0x51C` are implementation artifacts) rather than to format structure.
4. Against §5: Sun's width quanta may be expanded into `data-rule` thickness tokens (thin → 0.035, thick → 0.088, double → 0.092) as an advisory vocabulary only; normative token definitions remain with corpus-derived evidence.
5. Answer-ledger opportunity (§7.2 lead): with the generation point located (rule-carrying input → four-edge normalizer → `0x51C` record builder → cell/emitter chain), each `RuleFlowParser`/`RuleFlowAssembler` heuristic in our Kotlin pipeline can next be paired one-by-one against Sun's normalization decisions — as reference answers, to be accepted or rejected on corpus evidence.

### 20.6 Stream binding of the per-edge rule tables (direct-observation promotion)

The gap between JTD bytes and the four-edge cell model has now narrowed to a concrete chain:

* The reader opens `PageLayoutStyle` (stored at `this+0x225c`) and `TextLayoutStyle` (`this+0x2258`) from the container storage [L1]. Both stream names already appear in the RFC 0002 inventory.
* Each opened stream is addressed through item selector `0x10039920(stream, 1)` and `0x10039920(stream, 2)`: the style streams are used as **indexed-element containers** — a sub-item structure per stream [L1].
* A parse loop (`0x100169f8` and siblings) reads a count WORD from a live item cursor and allocates the record array at stride `0x584`; subsequent iterations alternate small-field cursor reads and stores at `[this+0x2444] + i*0x584 + off` [L1]. Call-census across one record pass: 13× WORD cursor (`0x1003a5b0`), plus DWORD cursor (`0x3a600`) and free-helper calls — **no single `0x584`-sized block read exists**; the loop consumes the stream field-by-field through the byte cursor (`0x1003a540`, a byte-at-a-time primitive).
* **Endianness**: the WORD cursor `0x1003a5b0` reads exactly 2 bytes via the byte cursor and swaps them (low→high, high→low); `0x3a600` does the same for 4 bytes — the style-table streams are **big-endian** [L1-direct]. This endianness is exhibited by the reader's own I/O primitive and independently corroborates the big-endian word reading established for `/DocumentText` (RFC 0006).
* Builders A (`0x1000eec0`) / B (`0x10017268`) then consume those records; the per-edge WORDs flow into `0x1001fdb0` × 4 (§20.4) → alias table → normalized edge codes → cell emitter.

Evidence status update: the byte-at-a-time cursor primitive, the big-endian WORD/DWORD encoding, the count WORD header, and the sub-item addressing are **direct observation [L1-direct]** — the cursor behavior fixes encoding and read fields even before any file dump. The strides `0x584`/`0x51C` are **RAM slot strides [L1-RAM]**; whether they equal the on-disk record stride remains **[L2]** (no block read observed; the loop is field-by-field). Also unresolved **[L2]**: the exact byte offsets and section boundaries of `TextLayoutStyle`/`PageLayoutStyle` inside the JTD container. Corpus task: locate both streams in samples, walk a record by replaying the observed field sequence (count WORD header, then the 13× BE WORD + DWORD field order), and test whether the bytes consumed per record match the RAM stride candidates.

### 20.7 Field order of one rule record (Sun reader level [L1-Sun-reader]; stride hypothesis eliminated)

Parse-side census replaces the fixed-stride hypothesis with a tagged, presence-bitmap encoding. Two layers were observed; both eliminate any on-disk `0x584`/`0x51C`.

**Layer 1 — `LayoutBox` stream object** (walker `0x10011970`, opened via `0x1003cde0(storage, "LayoutBox")`) [L1]:

* 8-byte header, skipped by `0x1003d330(obj, 8, 0)`; body walked with `remain = [obj+0x10] − 8`.
* Entry: TAG = BE WORD (`0x1003a6c0`). If TAG == `0x6668`: LEN = BE DWORD (`0x1003a710`); otherwise LEN = BE WORD. Payload = LEN bytes, drained byte-by-byte (`0x1003a680`) when not consumed structurally.
* TAG `0x201` (LEN observed bookkeeping `== 8`): payload = BE DWORD × 2 → stored sequentially at `[this+0x27f0 + idx*8]` (two 32-bit values; consumer role TBD [L2]).
* Bookkeeping per entry: TAG(2) + LEN(2 or 4) + payload. **No fixed record stride exists at this layer [L1]** — `0x584`/`0x51C` demoted to RAM-only layouts, finalizing §20.6.

**Layer 2 — style-table items of `TextLayoutStyle`** (three-pass fill at `0x100169f8` into the `0x584` RAM array) [L1]:

* Pass R (item #2 cursor): WORD `N` → alloc `N×0x584` [L1-RAM]; per record *i*: WORD `M_i` → rec+`0x538`, then `M_i`× BE WORD → rec+`0x540 + 2j`. On-disk consumption per record: `2 + 2·M_i` bytes.
* Pass K (item #1 cursor): WORD `K`; per group: WORD `L`, `L`× WORD key list into scratch, BE DWORD marker; the marker is written to `rec+0x53c` of every record whose tail array byte-matches the key list (`repe cmpsb`, `L·2` bytes).
* Pass P (per marker): sub-item via `0x10039920(TextLayoutStyle, marker)`; the item opens with BE WORD magic **`0x5555`**, then WORD skip-count + skip× WORD drain, then a WORD **value-width descriptor** `A`; then a **property opcode loop**: opcode WORD, `0xFFFF` = item end; dispatch via jump table `0x1001793a` over `0x5001`–`0x500B`:
  * `0x5001`/`0x5009` → handler `0x10017a50` (block = `this+0x26f4`, the `0x58C` alloc base): first a BYTE **presence bitmap** (stored at block+`0x00`); bitmap bits gate aligned slots `+0x01/+0x02/+0x04/+0x06/+0x08/+0x0A/+0x0C` (BYTE-or-WORD per jump table `0x10017e49`); then BE bytes → `+0x10`, `+0x11`; then the **four edge slots `+0x14/+0x18/+0x1c/+0x20`**, each read as **BE WORD when `A == 0x4003`, else BE DWORD** [L1]; then four bytes → `+0x24..+0x27`, two bytes → `+0x28/+0x29`; slot `+0x2c` read as BE DWORD when `A == 0x400a`, else BE WORD.
  * `0x5003` → BYTE array into `this+0x26fc` (loop count taken from the opcode word, clamped `0xfa`); `0x500A` → BE DWORD array into `this+0x26f8` (count = opcode word `>> 2`, clamped `0xfa` — capacity matches the `0x3E8`-byte builder slice exactly, §20.4).
  * `0x5004`/`0x5005`/`0x5006` → sub-parsers into `this+0x2700`/`+0x2704`/`+0x2708`; `0x500B` → scratch; `0x5002`/`0x5007`/`0x5008` → skip-drain.
* Connection to §20.4: these four edge slots are the producers of the `0x1001fdb0` × 4 normalization inputs; the builder's "WORD code at `+0x1e`" sits in the high half of the DWORD-encoded `+0x1c` slot — consistent, not contradictory.

Evidence status: the tag/opcode/bitmap **field order above is [L1]** (cursor-consumption order is fixed by the reader); edge identity (which of `+0x14..+0x20` is top/right/bottom/left), the semantic domain of slot values (§20.2 code map), the meaning of magic `0x5555`/`0x6668`/`0x201`, and consumer roles of `this+0x27f0` are **[L2]**. Corpus task refined: locate `TextLayoutStyle` items in samples, confirm magic `0x5555`, walk the property opcode loop, and match the four edge values against the `fo:border-*` outputs of the Sun filter on the same documents.

**Scope and version confound (2026-10-02, ruled-rectangle probe corpus).** Six probe documents of a single ruled rectangle (baseline; one bold side each: left/top/right/bottom; one with four distinct edge colors) were authored in **Ichitaro 2026**, extracted from the native CFBF container (directory streams `DocumentText`, `DocumentViewStyles`, `TextLayoutStyle`, `LineMark`, `PageMark`, ...), and scanned. Observed: **no stream in these native files contains the magic `0x5555`, the opcode space `0x5001`–`0x500B`, or the descriptor `0x4003`**. This negative result licenses **no attribution**: the jsreadermi filter serves the Ichitaro 8/9/10/11 generation (owner-supplied context), while this corpus is Ichitaro 2026 native. At least two hypotheses remain live for the §20.7 grammar: **H1 (spool layer)** — it is the Sun reader's own stream-object/internal representation produced while consuming native input; **H2 (older native dialect)** — it is native JTD on-disk encoding as of Ichitaro 8, re-encoded by later native versions. All grammar claims in this section are therefore reader-behavior observations **[L1-Sun-reader]**; native-byte attribution is **[L2-version-confounded]**. What does survive across generations is geometric continuity: same-size authored rectangles leave comparable, addressable geometry diffs even though style vocabularies diverge. The probe corpus's native byte observations are registered separately as evidence lineage **§20.8 [corpus-2026]**; nothing in this section is bound to them, and notably the §4.2 code vocabulary (`0x1c`/`0x22`) is **not reproduced in this 2026 corpus** — attribution involving version/layer differences remains open, and no identity is asserted either way. Adjudication experiments: (a) **author a controlled corpus in Ichitaro 8/9/10-era** (a Lite 2 contemporary of v10 is available; topological ground truth — which side is bold — suffices, pixel-exact geometry is not required) and scan native streams for the magic/opcode/descriptor — presence supports H2 directly (the reader reads native bytes), absence supports H1; (b) **capture the reader's spool during Sun conversion** (filesystem monitor) and compare spool bytes against the 2026 native streams.

### 20.8 Ruled probes authored in native 2026 — evidence lineage `[corpus-2026]`

Registered as an evidence lineage independent of §20.1–§20.7 (**[Sun-Ichitaro8]**). The two chains, not to be joined before §20.7 adjudication:

```text
[corpus-2026]    known edges (authored) → DocumentText byte diff → Tika parser events → RuleFlowAssembler
[Sun-Ichitaro8]  reader input → (native or spool: open, §20.7 H1/H2) → 0x5555 / property opcodes → 4 edge slots → ODF
```

**Probe corpus.** Six authored single-rectangle ruled documents: thin all sides, single thick edge per side, and four colored edges. Extracted from native CFBF directory streams (512 B sectors, mini-stream layout; scratchpad CFBF reader).

**Byte-diff facts [corpus-2026]:**

* Versus baseline, a bold **vertical** edge adds inline records in `DocumentText` (`07 02 00 01 …` / `02 02 00 01 …`, file growth `+0x104` bytes), placed behind `NN 00 00 31` marker records (calibrated later, §20.10: insertions correlate with metadata/template save events; byte-level rule claim retracted); the colored probe carries an additional vertical-family record whose trailing 4 bytes differ (`5c 4f 10 62` → `1a 4f 3e 79` class) — color-field candidate, semantics pending.
* A bold **horizontal** edge flips an in-place flag byte at `DocumentText+0x2F2` (`0x02`→`0x0c`) within a `07 02 00 01` record, identically for top and bottom — edge identity is encoded elsewhere in the record (byte-level claim calibrated by §20.10).
* All streams carry a common 48-byte header (`3090 … 0c09`) and systematic cross-file pollution (author strings, stamps such as `01 52 dd 01 …`); any diffing method must neutralize this noise (registering it as a known confound, not filtering it silently).
* The §4.2 code vocabulary (`0x1c`/`0x22`) is **not reproduced in this lineage**; version/layer attribution remains open and is asserted neither way.

**Open columns.** `DocumentText byte diff` is registered; `Tika parser events` and `RuleFlowAssembler` columns bind next via a parser harness over the probe corpus (behavior-named tests; no corpus text in assertions).

### 20.9 Lite 2 (8/9-era compatible) probes — lineage `[corpus-legacy]`, adjudication update

Same six authored configurations produced by Ichitaro Lite 2 (released beside v10; compatibility claimed near 8/9 — owner-supplied context), extracted from native CFBF containers. Findings:

* **Adjudication data point:** no stream in any Lite 2 file contains `0x5555`, `0x5001`–`0x500B`, or `0x4003` (both endian placements checked). With the 2026 corpus also negative, hypothesis **H2 (older native dialect) is cornered to authentic Ichitaro 8/9 originals** (still unfalsified — Lite 2 ≠ guaranteed 8/9 byte identity); **H1 (reader-side representation) is favored but remains unproven**. No join into §20.7 is registered.
* **Container dialect difference:** Lite 2 containers carry no `TextLayoutStyle` stream at all (2026 does); stream sets differ by generation [corpus-legacy].
* **Record framing continuity:** `DocumentText` is a stable ledger of `NN 00 00 31` records whose payload labels decode (UTF-16LE) to author-metadata records (`作成`, `作成時間`, `前回更新`, `ページ数`, `会社`, …). Record tags are stable across files; edits *append* records (bold-vertical variants add tag `0x07` and `0x16`; the colored variant appends four records, file growth `+0x30C`) [corpus-legacy].
* **Confounds identified:** paired 4-byte windows in `DocumentText` (`0x380`, `0x588`) and in `DocumentViewStyles` (`0x380`) change whenever any side changes, and differ between files whose semantic edit differs in only one dimension — consistent with **per-save checksum/stamp windows**, not edge fields; the `52 dd 01` stamp family occurs in both generations. Raw red (`ff 00 00`) was located once in the colored file (`DocumentText+0x949`); blue/green/yellow were not found as raw RGB — mixed palette/index encoding suspected, semantics pending.
* **Methodological pivot — decisive probes:** cross-file diffing is saturated by stamps/pollution. Two controlled probes separate semantics from noise: (P1) **double-save** any single file without edits — bytes that change are stamps, the rest calibrates noise; (P2) **two rectangles in one file** (one thick-vertical, one thin-all-around) — edge↔record binding happens within a single file with zero cross-file noise. P2 is the primary ground-truth instrument and does not require pixel-exact placement.

Parser-side binding (parser events → `RuleFlowAssembler`) remains open across `[corpus-2026]` and `[corpus-legacy]`.

**P2 single-file probe (two-rectangle document, [corpus-legacy]) — registered observations.** `DocumentText` gains a trailing ledger of ASCII-numbered units `'1'..'7'`, each with subfields `NN 04/05/06/07`: sub04 carries geometry words with bit-`0x80` flags (consistent with the §4.2 wall-word convention `b_0=80`); sub05/sub07 are identical across units 2–6; **sub06 varies per unit** (`06 03 00 09 01 07 08` / `07 03 00 0b 01 00 07 d0` / `06 03 00 0a 00 …` / `06 03 00 0a 03 …` ×2 / `05 03 00 08 …`) — a per-band rule ledger candidate. Two open questions block binding: (i) units 2–6 share identical sub04 geometry, so the two asymmetric rectangles are not yet distinguishable inside this ledger (either only one bbox range is recorded at this layer, or the second range lives elsewhere); (ii) authored ground truth (which rectangle carries which bold edges/widths/colors) is to be confirmed by the owner. `0x1c`/`0x22` again unreproduced in this file.

### 20.10 Calibration by double-save — REVISED (authoritative re-measurement; prior fingerprint-fraction claims withdrawn)

Re-measurement of the unedited resave pair with the authoritative reader (POIFS, production-equivalent; §20.11 erratum):

* **Content layer is byte-stable across saves**: `DocumentText`, `Font`, `Header`, `LineMark`, `PageMark`, `PaperMark`, `DocumentEditStyles` are byte-identical original-to-resave. The earlier claims of save counters at `+0x14`/`+0x20` in every stream and of `DocumentText` fingerprint movement are **withdrawn** — they were extractor artifacts.
* **Volatile layer is revision/metadata/view only**: `JSRV_SummaryInformation` (grows +112 B; carries the revision stamp family `NN 52 dd 01`), `JSRV_SegmentInformation` (scattered diffs), `SummaryInformation` (grows +44 B), `DocumentViewStyles` (grows +4 B; exactly four 1–2-byte view-state edits). Save volatility lives entirely in this layer.
* Containers differ only in physical sector placement; logical streams read through POIFS are identical for the stable set.
* Standing methodology note: binding still goes through the parser's deterministic walk — now justified by cross-generation address drift and the volatile metadata layer, not by claimed per-save content churn.
* Font-record family `03 02 01` (+ `12 00` + "Times New Roman") identification stands.
* The §20.9 ledger / uniqueness bind (`d0`/`0b`) is void: the ledger itself was an extraction artifact (§20.11).

### 20.11 Wall-style entries and `WallRule` events (P2 resolution, [corpus-legacy], 2026-10-02)

**Extractor erratum (applies to §20.9/§20.10).** The scratch CFBF extractor used during that phase had a FAT-chain ordering defect; its byte dumps, addresses, and the "ASCII-unit ledger" reported there are extraction artifacts and are withdrawn. With an authoritative reader, `/DocumentText` of the P2 probe and its unedited resave is **byte-identical** through pipeline traversal (containers differ only in physical sector placement), and the same §4-grammar `SsmgV.01` header appears in both generations. The "save-unreproducible fingerprint / ledger re-encoding" claims are superseded: save round-trip behavior is settled as **invariance at the parser level**, verified on rail `CorpusRuledBoxGroundTruthRailTest` (oracle-1).

**Wall-style grammar (trailing style region, observed)** — entries `[id] FE [sub1] [sub2] [style WORD] …`:

| entry | meaning | evidence ([corpus-legacy]) |
|---|---|---|
| `FE 02 02` + style `0x0003` | bold vertical wall | bold-left probe, bold-right probe, P2 narrow box |
| `FE 03 02` + style `0x0003` | bold horizontal wall | bold-top probe, bold-bottom probe |
| `FE 02 02` + style `0x0000` | thin vertical wall | trailing entries of bold probes |
| `FE 01 02` / `FE 10 04` / `FE 0F 04` | reserved (unresolved) | repeated interior entries; colored-probe color families |

A document whose edges are all thin carries **no** `FE 02/03` entries — thin is default and only bold is declared. The leading id byte varies with wall placement across probes of the same side; id→side decoding is deferred to a later phase, so the rail pins orientation, weight, and counts, not side.

**Event contract.** `FlowEvent.WallRule(orientation, weight)` — module-internal, produces no `FlowBlock`, XHTML output invariant. Rail `rulebox.rail` asserts, per round-trip pair member: oracle-1 identical payloads/events/blocks across the unedited resave (semantic invariance against save re-encoding — explicitly *not* a correctness proof); oracle-2 exactly one thick vertical wall and zero thick horizontal walls, the semantic projection of the owner-confirmed ground truth "the narrow leading box has a bold leading edge; all other edges and the wide box are thin."

**No joining.** None of the above touches the §20.7 Sun-reader vocabulary; [Sun-Ichitaro8] remains separate and unjoined.

**Open item (pre-existing).** The `flowstruct` rail T3-1 check now fails with 30 in-cell `<br>` remaining; this predates the §20.11 change (reproduced with it reverted) and is likely the known interaction with §13.2 tall-cell `LineBreak` junctions. Adjudication of the rail's acceptance text against §13.2 is tracked separately.

### 20.12 Measurement discipline (normative, 2026-10-02)

**Native byte observations SHALL be obtained through production-equivalent POIFS traversal, or independently cross-validated, before being promoted to corpus evidence.**

Rationale (the §20.11 incident — "the instrument produced the phenomenon"): a scratch CFBF extractor with a FAT-chain ordering defect manufactured apparent per-save relayout, phantom ledgers, and fake fingerprint noise in streams that the production pipeline reads byte-stable. Self-verification gates broke the hypothesis stack before it was completed, but the discipline is now normative:

1. Byte addresses, diffs, and counts registered in this RFC MUST be re-derivable from (a) the production parser traversal (POIFS via `JtdContainerReader`) or (b) a second independent reader.
2. Otherwise they are recorded as scratch observations with an explicit instrument caveat, never as corpus evidence.
3. Semantic rails SHALL not depend on raw byte fingerprints of the measurement stage itself (the oracle-1/oracle-2 split of §20.11 stands as the pattern).

### 20.13 Wall side/color bind — P3 controlled probes with PDF visual oracle ([corpus-legacy], 2026-10-02)

Method: P3 single-variable probes authored in Lite 2; visual truth obtained by converting to Ichitaro 2026 and exporting PDF, parsing PDF vector rects (filled-bar rendering; thin = 0.6pt, thick = 1.92pt; fill color = edge color). This keeps the oracle independent of any JTD reader (no circularity) and demonstrates cross-generation layout fidelity of the conversion.

Evidence-split bind table (provenance per row, [corpus-legacy] new sub-dialect unless noted):

| claim | status | instrument |
|---|---|---|
| entry id `09` → leading (left) wall | **direct bind** | P3e: unique thick-red left edge (PDF) vs unique bold-color entry id 09; corroborated by p3a/p3c |
| entry id `01` → trailing (right) wall | **direct bind** | P3e: unique thick-blue right edge (PDF) vs second bold-color entry id 01; corroborated by p3b |
| axis byte `02`→vertical, `03`→horizontal | bind | p3d multi-entry list `(02,02,B)(03,02,B)` under one marker + p3b/p3e vertical-only, old top/bottom probes |
| top/bottom individual side ids | **open** | old sub-dialect shows bold horizontals `0e`(top-sample)/`96`(bottom-sample) — single samples, not uniquely adjudicated; new-dialect horizontals unresolved |
| color declaration = sub-entry `(axis, 04, …)` present | bind | P3c/P3e add color subs absent in black-only probes; `ff 00 00` appearing in every probe (93x) is the default-pen field, not a color declaration |
| color payload contains RGB24 windows (red/blue distinguishable per side) | bind (byte-alignment open) | P3e entries 09/01 carry distinct color subs; exact field order (flag/RGB/alpha alignment) open pending one thin-colored probe |
| multi-entry sub-list per marker | bind | p3d |
| bold-vertical entry append pattern | bind | p3b: bold unit appended per bolded vertical |
| sub-dialect split: old ids `{0e,12,0c,96}` vs new ids `{09,01,03}` | registered observation | old single-edge suite vs P3 suite (suspected authoring-operation difference) |

§20.12 amendment (methodology): a second, independent instrument class is hereby sanctioned — **the Writer/PDF rendering of the same document, across a reader generation boundary, as a visual oracle** — in addition to POIFS traversal cross-validation.

Probe factory closure: P3/P3e are declared sufficient for side/weight/color/orientation bind at the registered confidence levels. Remaining open items: top/bottom side ids; color byte-alignment; flowstruct rail T3-1 (§13.2 adjudication); H1/H2. No joining with [Sun-Ichitaro8] (§20.7).

### 21. T3-1 rail resync — per-cell oracle identity (adjudicated 2026-10-02; registration only, no behavior change)

Substance: a stale frozen-count rail (v1 `br-in-td=0` → 2026-09-30 re-registration `<= 6`) is replaced by an **oracle-derived invariant** — the ground-truth owner-reproduced HTML and the current parser output must have identical per-cell `<br/>` count vectors in document order over the flat table (opt-in rail test `inCellBrVectorIdentityVsOracle`). This is a resync of measurement, not a change of contract: §13.2/§14.6/§15.4 already require merged seams to project `FlowPart.LineBreak` → `<br/>`; the frozen 6 was registered against §13.2 calendar seams and was never re-measured for the §9.13 fingerprint document after §16 landed.

Historical evidence (provenance, not contract):

* `b934db3` (§14 era, 2026-09-30): the fingerprint document emitted tr=57 / br=0. The v1 "zero in-cell br" was an artifact of unmerged rows (ground truth tr=9), never the document's truth.
* §16 (`7a48a0a`, 2026-10-01) delivered tr 57→9 for exactly this document (§16.5), legitimately growing seam counts.
* Per-cell oracle (2026-10-02): ground-truth in-cell br total = 179 (all of it inside cells, consistent with the §9.13 tribe br=179); parser output = 175; 122/128 cells identical.

Retractions (§20.12 discipline):

* The figure "br-in-td=30 on both sides" quoted during the adjudication is a skeleton-regex artifact — the cell pattern matched `<td>` only while ground-truth header cells are `<th>`. Flat per-cell parsing supersedes it.
* Consequently retracted: "the residual br total difference 175 vs 179 lives outside tables". It does not; the difference is in-cell (tracked as O2).

Tracked item O2 (registered in §7.2): tall/wide-cell seam-placement fingerprint — cell index (document order), oracle count, output count, colspan/rowspan identical on both sides:

| idx | gt | out | attrs |
|---|---|---|---|
| 44 | 10 | 9 | colspan=2 |
| 53 | 10 | 9 | rowspan=3 colspan=2 |
| 54 | 10 | 9 | rowspan=3 colspan=3 |
| 76 | 13 | 12 | rowspan=3 colspan=3 |
| 79 | 5 | 6 | colspan=4 |
| 81 | 14 | 13 | rowspan=3 colspan=3 |

Row cell-sizes are identical on both sides ([32,15,13,8,4,12,8,4,32]) and the attributes above match, so O2 is not row-merge drift; it localizes to in-cell seam decisions (§13.2 fold signals vs §14.2 closed-cell rule) at ±1 line per cell. Until O2 adjudicates, the strict rail **fails by design** (registered red); the structural skeleton rail (`railAgainstGroundTruthSkeleton`, Markdown pipe-row cap re-affirmed at 5 on 2026-10-02) stays green and guards regressions.

Documentation errata (no behavior change, separate classification):

* The EN master contains no §12/§12.4/§12.5 section bodies; cross-references in §13.2/§14.6 (and test KDoc) to "§12.4/§12.5 T3-1" point at pre-renumbering section bodies that were folded into later addenda. Correspondence: cited T3-1 = the frozen-count acceptance criteria now superseded by this §21. References are kept as written to preserve provenance; no renumbering.

### 22. O2 census — seam intent not inferable from native structure (2026-10-02; registration only, no behavior change)

Substance: the O2 ±1 seam residuals (§21) are adjudicated as **not a production-rule bug**. The seam choice at vertically continued single-CJK-character lines is oracle-only writing intent and is currently **not derivable from the native fingerprint**. No new discriminator SHALL be added to `joinVerticalRun` to guess owner intent.

Census method (disclosure-safe): production pipeline executed end-to-end out-of-repo via friend-module access (`payload → RuleFlowParser → RuleFlowAssembler`), self-verified against the full SAX output (`cells=128`, in-cell seam total `175`, per-cell match true). Only integers, span coordinates, word values and character-class histograms were observed.

Fixed points:

1. **Old hypothesis rejected.** The §13.2-merge vs §14.2-closure distinction does not explain O2. All 844 span declarations in the document carry word-6 = `0x00ff` (and word-7 = `0x0000`); the §14 R2 closed-cell conflict, the §19 closed-family (bit0) boundary and the §14 R4 down-anchor (`word-7 = 0x0002`) never fire in this document. Closure suppression is not the suppressor.
2. **word-6 census.** Target population (all span declarations): 844/844 = `0x00ff` (standard/continuation). Zero closed-family values.
3. **Parser side fully explained by the existing §16v2 `joinVerticalRun` character-class branches.** Predicted seam counts from the branch rule (all-digit → space, pure-ASCII → space, `len == 1` → direct concatenation, otherwise `LineBreak`) equal the parser's actual in-cell `LineBreak` counts in every aligned occurrence window. All five −1 cells (indices 44/53/54/76/81; residual −1 each, subtotal −5) localize to single-CJK-character joins flattened by `len == 1`. The +1 cell (index 79) is an owner-side row fusion, the mirror direction; net −4.
4. **Oracle-side 98/5 split is native-indistinguishable.** Under the identical native signature (`0x00ff` + WRAP-terminated vertical continuation declarations), the owner-reproduced HTML concatenates without `<br>` in 98 of 128 cells (zero br anywhere in the cell) and preserves per-line `<br>` in 30 cells (total oracle br 179). Cross-tabulation of predicted non-`LineBreak` joins by cell coverage: control cells — 46 WRAP + 2 non-WRAP single-CJK joins, all in zero-br cells; diff cells — 3 WRAP + 2 non-WRAP, all in full-coverage (br-per-line) cells. No observed native feature separates the two buckets.

Consequence: O2 is reclassified from "parser seam loss pending fix" to **"oracle-only seam intent / not inferable from native structure"**. The strict rail `inCellBrVectorIdentityVsOracle` remains registered-red; that redness is now evidence of unresolved writing intent, not of a decode defect. This registration neither turns the rail green nor licenses a production change.

Open question to the owner (normative choice required before any join-rule change):

> For vertically continued single-CJK-character lines inside tall cells, which is normative?
> 1. Always preserve each displayed wrapped line as an explicit `<br>`;
> 2. Concatenate the characters and rely on narrow-cell/browser wrapping;
> 3. Some other criterion (please describe).
>
> Once confirmed, the oracle HTML will be regenerated under the confirmed rule and only then will parser join rules be evaluated against it.

Documentation errata (no behavior impact): the owner-reproduced HTML uses non-self-closing `<br>`; all seam-count comparisons are tag-prefix matches and are unaffected.
