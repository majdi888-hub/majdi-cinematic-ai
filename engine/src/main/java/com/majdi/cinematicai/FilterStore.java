package com.majdi.cinematicai;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imported filters and personal presets, kept in the app's private folder.
 *
 * Import formats, each with a real parser (data only: nothing in a file is ever executed):
 *  - .cube  Adobe/Resolve 3D LUT
 *  - .3dl   Autodesk Lustre/Flame 3D LUT (10/12/16-bit integers, blue fastest)
 *  - .json  Majdi preset ("format": "majdi-filter" or "majdi-edit"): stack layers + Adjust values,
 *           every value range-checked
 *  - .zip   a pack of the above (no nested archives, entry count and size limits; entry names are
 *           never used as paths, so path traversal is impossible; suspicious names are rejected)
 *
 * Every LUT is stored re-encoded as a validated .cube; every preset as re-serialized JSON.
 */
final class FilterStore {

    static final long MAX_FILE = 25L << 20;       // 25 MB per file
    static final int MAX_ZIP_ENTRIES = 64;
    static final long MAX_ZIP_TOTAL = 100L << 20;   // 100 MB uncompressed

    static final class Item {
        String id, name, kind, category;           // kind: "lut" | "preset"
        Item(String id, String name, String kind, String category) {
            this.id = id; this.name = name; this.kind = kind; this.category = category;
        }
    }

    static final class Report {
        final List<Item> added = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();   // "name: reason"
    }

    private final File dir;
    private final List<Item> items = new ArrayList<>();

    FilterStore(File dir) throws IOException {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File idx = new File(dir, "index.json");
        if (idx.isFile()) {
            try {
                Map<String, Object> m = Json.obj(Json.parse(read(new FileInputStream(idx), MAX_FILE)));
                for (Object o : Json.arr(m.get("items"))) {
                    Map<String, Object> it = Json.obj(o);
                    String id = Json.str(it, "id", "", 40);
                    if (!id.matches("[A-Za-z0-9_\\-]{1,40}")) continue;
                    items.add(new Item(id, Json.str(it, "name", "filter", 80), Json.str(it, "kind", "lut", 8),
                            Json.str(it, "category", "مستوردة", 30)));
                }
            } catch (RuntimeException e) {
                // damaged index: start empty rather than crash (files are kept)
            }
        }
    }

    List<Item> list() { return new ArrayList<>(items); }

    Item get(String id) {
        for (Item i : items) if (i.id.equals(id)) return i;
        return null;
    }

    /** Imports one picked file (by its display name) and returns what was added or rejected. */
    Report importFile(String fileName, InputStream in) throws IOException {
        Report r = new Report();
        byte[] data;
        try {
            data = read(in, MAX_FILE);
        } catch (IOException e) {
            r.rejected.add(fileName + ": " + e.getMessage());
            return r;
        }
        String ext = ext(fileName);
        if (ext.equals("zip")) importZip(data, r);
        else importOne(fileName, data, r, "مستوردة");
        if (!r.added.isEmpty()) saveIndex();
        return r;
    }

    private void importZip(byte[] data, Report r) throws IOException {
        int n = 0;
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (++n > MAX_ZIP_ENTRIES) { r.rejected.add("zip: more than " + MAX_ZIP_ENTRIES + " files"); break; }
                if (name.contains("..") || name.startsWith("/") || name.contains("\\") || name.indexOf(':') >= 0) {
                    r.rejected.add(name + ": unsafe path in archive");
                    continue;
                }
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith(".") || name.startsWith("__MACOSX")) continue;
                String ext = ext(base);
                if (!ext.matches("cube|3dl|json")) {
                    r.rejected.add(base + (ext.equals("zip") ? ": nested archives are not supported" : ": unsupported type"));
                    continue;
                }
                byte[] b;
                try {
                    b = read(z, Math.min(MAX_FILE, MAX_ZIP_TOTAL - total));
                } catch (IOException ex) {
                    r.rejected.add(base + ": " + ex.getMessage());
                    break;
                }
                total += b.length;
                importOne(base, b, r, "مستوردة");
            }
        } catch (IOException | IllegalArgumentException ex) {
            r.rejected.add("zip: damaged archive (" + ex.getMessage() + ")");
        }
    }

    private void importOne(String fileName, byte[] data, Report r, String category) throws IOException {
        String ext = ext(fileName);
        String name = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        name = cleanName(name);
        try {
            switch (ext) {
                case "cube": addLut(Lut3D.parse(new ByteArrayInputStream(data)), name, category, r); break;
                case "3dl": addLut(Lut3D.parse3dl(new ByteArrayInputStream(data), name), name, category, r); break;
                case "json": {
                    Map<String, Object> m = Json.obj(Json.parse(new String(data, StandardCharsets.UTF_8)));
                    String fmt = Json.str(m, "format", "", 20);
                    if (!fmt.equals("majdi-filter") && !fmt.equals("majdi-edit"))
                        throw new IllegalArgumentException("not a Majdi filter file");
                    Map<String, Object> edit = fmt.equals("majdi-filter") && m.get("edit") != null ? Json.obj(m.get("edit")) : m;
                    EditState st = EditState.fromMap(edit);
                    if (st.layers.isEmpty() && !st.hasAdjust()) throw new IllegalArgumentException("empty preset");
                    String nm = cleanName(Json.str(m, "name", name, 60));
                    addPreset(st, nm, category, r);
                    break;
                }
                default:
                    r.rejected.add(fileName + ": unsupported type (.cube, .3dl, .json, .zip)");
            }
        } catch (IOException | RuntimeException e) {
            r.rejected.add(fileName + ": " + e.getMessage());
        }
    }

    private void addLut(Lut3D lut, String name, String category, Report r) throws IOException {
        String id = newId();
        try (FileOutputStream os = new FileOutputStream(new File(dir, id + ".cube"))) { lut.writeCube(os); }
        Item it = new Item(id, name, "lut", category);
        items.add(it);
        r.added.add(it);
    }

    private void addPreset(EditState st, String name, String category, Report r) throws IOException {
        String id = newId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "majdi-filter");
        m.put("version", 1);
        m.put("name", name);
        m.put("edit", lookOnly(st).toMap());
        try (FileOutputStream os = new FileOutputStream(new File(dir, id + ".json"))) {
            os.write(Json.write(m).getBytes(StandardCharsets.UTF_8));
        }
        Item it = new Item(id, name, "preset", category);
        items.add(it);
        r.added.add(it);
    }

    /** Saves the current look (stack + Adjust) as a personal preset. */
    Item savePersonal(EditState st, String name) throws IOException {
        Report r = new Report();
        addPreset(st, cleanName(name), "شخصية", r);
        saveIndex();
        return r.added.get(0);
    }

    /** The look part of an edit: layers + Adjust (no crop, frame or focus point). */
    static EditState lookOnly(EditState st) {
        EditState s = new EditState();
        for (EditState.Layer l : st.layers) s.layers.add(l.copy());
        s.adjust.putAll(st.adjust);
        return s;
    }

    Lut3D loadLut(String id) throws IOException {
        Item it = get(id);
        if (it == null || !it.kind.equals("lut")) return null;
        try (InputStream in = new FileInputStream(new File(dir, id + ".cube"))) { return Lut3D.parse(in); }
    }

    EditState loadPreset(String id) throws IOException {
        Item it = get(id);
        if (it == null || !it.kind.equals("preset")) return null;
        Map<String, Object> m = Json.obj(Json.parse(read(new FileInputStream(new File(dir, id + ".json")), MAX_FILE)));
        return EditState.fromMap(Json.obj(m.get("edit")));
    }

    boolean delete(String id) throws IOException {
        Item it = get(id);
        if (it == null) return false;
        items.remove(it);
        new File(dir, id + (it.kind.equals("lut") ? ".cube" : ".json")).delete();
        saveIndex();
        return true;
    }

    void rename(String id, String name) throws IOException {
        Item it = get(id);
        if (it != null) { it.name = cleanName(name); saveIndex(); }
    }

    void setCategory(String id, String category) throws IOException {
        Item it = get(id);
        if (it != null) { it.category = cleanName(category); saveIndex(); }
    }

    private void saveIndex() throws IOException {
        List<Object> ls = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", i.id); o.put("name", i.name); o.put("kind", i.kind); o.put("category", i.category);
            ls.add(o);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", ls);
        File tmp = new File(dir, "index.json.tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(Json.write(m).getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(new File(dir, "index.json"))) throw new IOException("cannot save filter index");
    }

    private static String newId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    static String cleanName(String s) {
        String t = s.replaceAll("[\\p{Cntrl}/\\\\]", " ").trim();
        if (t.isEmpty()) t = "filter";
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    static byte[] read(InputStream in, long max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long n = 0;
        int k;
        while ((k = in.read(buf)) > 0) {
            n += k;
            if (n > max) throw new IOException("file too large");
            bo.write(buf, 0, k);
        }
        return bo.toByteArray();
    }

    static String read(InputStream in, long max, boolean close) throws IOException {
        try { return new String(read(in, max), StandardCharsets.UTF_8); } finally { if (close) in.close(); }
    }

    private static String read(FileInputStream in, long max) throws IOException {
        return read(in, max, true);
    }
}
