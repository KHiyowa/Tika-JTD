# RFC 0011: ObjectBox and Embedded Document Extraction

Status: accepted

Observed / Established: 2026-09-27

Japanese version: [0011-object-box-embedded-documents.ja.md](0011-object-box-embedded-documents.ja.md)

## Summary

In JustSystems Ichitaro documents (`.jtd` / `.jtt`), external components such as spreadsheets, graphics, and OLE objects embedded within the document are maintained as **"ObjectBoxes" (オブジェクト枠)**, distinct from primary document streams such as `/DocumentText`, `/Header`, and `/LayoutBoxText`.

While RFC 0008 (*Object and Embedded Image Stream Candidates*) documented candidate streams under diagnostic observation (`Status: Diagnostic only`), this specification formally defines the binary layout, normalization procedures, and extraction lifecycle for these ObjectBox components.

---

## 1. CFB Storage Structure and Discovery Rules

ObjectBox components are stored within dedicated sub-storages inside the Compound File Binary (CFB) container.

### 1.1 Representative Storage Layout

```text
/Embedding 1/                                (Storage: ObjectBox 1)
/Embedding 1/\x03EmbeddedPress               (Stream: Graphic press / WMF)
/Embedding 1/\x03EmbeddedPress2              (Stream: Secondary graphic press)
/Embedding 1/Workbook                        (Stream: Raw BIFF8 spreadsheet)
/Embedding 2/                                (Storage: ObjectBox 2)
/Embedding 2/Workbook
...
/OleItem 1/                                  (Storage: OLE ObjectBox)
/OleItem 1/\x03EmbeddedPress
/OleItem 1/Workbook
```

### 1.2 Discovery and Traversal Rules

1. **Storage Classification**:
   - Any directory entry whose name starts with `Embedding` or `OleItem` (case-insensitive) is identified as an ObjectBox storage.
2. **Deterministic Ordering**:
   - Directory entries must be traversed in case-insensitive lexicographical order (sorted by lowercase entry name) to guarantee reproducible extraction sequences across runs.
3. **Traversal Boundaries and Resource Limits**:
   - **Maximum Traversal Depth**: `MAX_EMBEDDED_DEPTH = 4` (traversal stops at depth >= 5).
     > **Note**: Based on findings in the subsequent image/EMF expansion work (`feat/embedded-images-and-emf`), this depth limit is scheduled to be updated to `8`.
   - **Maximum Object Count**: The total number of delegated objects per document is bounded by `MAX_EMBEDDED_OBJECTS = 64`.

---

## 2. Spreadsheet ObjectBox (`Workbook`) Normalization

### 2.1 Binary Layout

A `Workbook` stream inside an ObjectBox takes one of two forms:

1. **Raw BIFF8 / OLE 1.0 Stream**:
   - Starts with `0x09 0x08 0x10 0x00` (BIFF8 BOF record: Type `0x0809`, Length `0x0010`).
   - A raw Excel workbook byte sequence lacking an outer CFB compound file header (`0xD0 0xCF 0x11 0xE0`).
2. **Standard OLE2 CFB Container**:
   - A complete, self-contained CFB container file.

### 2.2 Normalization Protocol

Because standard document parsers (such as Apache POI HSSF) expect Excel workbooks to be encapsulated inside an OLE2 container, raw BIFF8 streams require on-the-fly normalization:

- Construct an empty in-memory OLE2 CFB container (`POIFSFileSystem`) and write the raw BIFF8 payload into a top-level stream named `"Workbook"`.
- Streams that are already valid CFB containers are passed through unmodified.

### 2.3 Metadata Contract

- **Content-Type**: `application/vnd.ms-excel`
- **Resource Name**: `embedded-${index}.xls` (`index` is a zero-based sequence counter)
- **Relationship ID**: Original CFB path of the stream (e.g., `/Embedding 1/Workbook`)

---

## 3. Graphic Press ObjectBox (`EmbeddedPress`) Slicing

### 3.1 Binary Layout

Streams named `\x03EmbeddedPress` or `\x03EmbeddedPress2` store vector graphics and metafile rendering payloads.

- **Magic Header**: The first 8 bytes must match the ASCII magic `"METAFILE"` (`0x4d 0x45 0x54 0x41 0x46 0x49 0x4c 0x45`).
- **Header Prefix**: A JustSystems proprietary header block follows the magic.
- **WMF Header Marker**: Within an offset window from `0x08` to approximately `0x60` (scanned on even-byte boundaries), a standard Windows Metafile header record marker (`0x01 0x00 0x09 0x00`) appears.

### 3.2 Slicing Protocol

1. If the first 8 bytes do not match `"METAFILE"`, the stream is rejected as an invalid graphic press and skipped.
2. Scan the prefix range starting at offset 8 in even increments to locate the WMF marker `0x01 0x00 0x09 0x00`.
3. Slice the payload from the located marker offset to the end of the stream as the valid WMF byte sequence.

### 3.3 Text Content Characteristics

The extracted WMF stream contains Shift_JIS-encoded text records (`ExtTextOut` / `TextOut`). These records hold chart axis labels, legends, captions, table contents, or sections of the primary document body rendered as vector shapes.

### 3.4 Metadata Contract

- **Content-Type**: `image/wmf`
- **Resource Name**: `embedded-${index}.wmf`
- **Relationship ID**: Original CFB path of the stream (e.g., `/Embedding 1/\x03EmbeddedPress`)

---

## 4. Embedded Document Extraction Protocol

### 4.1 Pipeline Integration

The primary document parser (`JtdParser`) initiates embedded document extraction immediately after completing the primary text stream emission (`/DocumentText`, `/Header`, `/LayoutBoxText`) and before closing the enclosing XHTML `<body>` element.

```
[ JtdParser.parse ]
     │
     ├─ 1. Primary Text XHTML Emission (DocumentText / Header / LayoutBoxText)
     │
     ├─ 2. ObjectBoxExtractor.extractEmbeddedDocuments
     │       ├─ CFB Directory Walk (Embedding / OleItem)
     │       ├─ Binary Normalization (Raw BIFF8 CFB Wrap / WMF Slice)
     │       └─ Delegation to Tika EmbeddedDocumentExtractor
     │
     └─ 3. Close Parent XHTML Body
```

### 4.2 Integration with Tika `EmbeddedDocumentExtractor`

1. The extractor evaluates `extractor.shouldParseEmbedded(metadata, context)`. If accepted, the delegation counter `index` is incremented.
2. The extractor invokes `extractor.parseEmbedded(stream, handler, metadata, context, false)` to stream child content into the parent XHTML `ContentHandler`.

### 4.3 Fault Isolation

Any parser or I/O exceptions thrown while processing individual embedded objects are trapped and logged locally. This ensures that failures in nested objects do not abort processing of sibling objects or compromise extraction of the primary document body.
