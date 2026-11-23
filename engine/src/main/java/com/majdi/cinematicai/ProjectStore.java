package com.majdi.cinematicai;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Editing projects: each one keeps an untouched copy of the original photo plus the
 * non-destructive edit (stack, Adjust, geometry, frame, lens) so it can be reopened and changed later.
 *   projects/<id>/original      byte-for-byte copy of the picked file
 *   projects/<id>/edit.json     EditState
 *   projects/<id>/meta.json     name, timestamps
 *   projects/<id>/thumb.jpg     written by the app
 */
final class ProjectStore {

    static final class Project {
        String id, name;
        long created, modified;
        File dir;
        File original() { return new File(dir, "original"); }
        File thumb() { return new File(dir, "thumb.jpg"); }
    }

    private final File root;

    ProjectStore(File root) throws IOException {
        this.root = root;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
    }

    Project create(InputStream original, String name, EditState st) throws IOException {
        Project p = new Project();
        p.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        p.dir = new File(root, p.id);
        if (!p.dir.mkdirs()) throw new IOException("cannot create project");
        try (FileOutputStream os = new FileOutputStream(p.original())) {
            byte[] b = new byte[65536];
            int k;
            while ((k = original.read(b)) > 0) os.write(b, 0, k);
        }
        p.name = FilterStore.cleanName(name);
        p.created = p.modified = System.currentTimeMillis();
        save(p, st);
        return p;
    }

    void save(Project p, EditState st) throws IOException {
        p.modified = System.currentTimeMillis();
        write(new File(p.dir, "edit.json"), st.toJson());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name);
        m.put("created", (double) p.created);
        m.put("modified", (double) p.modified);
        write(new File(p.dir, "meta.json"), Json.write(m));
    }

    EditState loadEdit(Project p) throws IOException {
        try (FileInputStream in = new FileInputStream(new File(p.dir, "edit.json"))) {
            return EditState.fromJson(FilterStore.read(in, Json.MAX_LENGTH, false));
        }
    }

    List<Project> list() {
        List<Project> out = new ArrayList<>();
        File[] ds = root.listFiles();
        if (ds == null) return out;
        for (File d : ds) {
            if (!d.isDirectory() || !d.getName().matches("[0-9a-f]{16}")) continue;
            try (FileInputStream in = new FileInputStream(new File(d, "meta.json"))) {
                Map<String, Object> m = Json.obj(Json.parse(FilterStore.read(in, 100_000, false)));
                Project p = new Project();
                p.id = d.getName();
                p.dir = d;
                p.name = Json.str(m, "name", "project", 80);
                p.created = (long) ((Number) m.get("created")).doubleValue();
                p.modified = (long) ((Number) m.get("modified")).doubleValue();
                if (p.original().isFile()) out.add(p);
            } catch (IOException | RuntimeException e) {
                // skip damaged project folders
            }
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    boolean delete(Project p) {
        File[] fs = p.dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        return p.dir.delete();
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new IOException("cannot save " + f.getName());
    }
}
