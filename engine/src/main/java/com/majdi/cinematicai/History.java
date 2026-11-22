package com.majdi.cinematicai;

import java.util.ArrayDeque;
import java.util.Deque;

/** Undo/redo of complete edit states (stored as JSON snapshots, so every step is exact). */
final class History {

    private static final int LIMIT = 100;
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private String current;

    History(EditState initial) {
        current = initial.toJson();
    }

    /** Records a new state; returns false if nothing changed. */
    boolean push(EditState s) {
        String j = s.toJson();
        if (j.equals(current)) return false;
        undo.push(current);
        if (undo.size() > LIMIT) undo.removeLast();
        current = j;
        redo.clear();
        return true;
    }

    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    EditState undo() {
        if (undo.isEmpty()) return EditState.fromJson(current);
        redo.push(current);
        current = undo.pop();
        return EditState.fromJson(current);
    }

    EditState redo() {
        if (redo.isEmpty()) return EditState.fromJson(current);
        undo.push(current);
        current = redo.pop();
        return EditState.fromJson(current);
    }

    EditState current() {
        return EditState.fromJson(current);
    }
}
