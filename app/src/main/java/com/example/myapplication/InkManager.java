package com.example.myapplication;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class InkManager {
    private final Deque<InkStroke> done = new ArrayDeque<>();
    private final Deque<InkStroke> undone = new ArrayDeque<>();

    public void add(InkStroke s) { done.addLast(s); undone.clear(); }
    public boolean canUndo() { return !done.isEmpty(); }
    public boolean canRedo() { return !undone.isEmpty(); }
    public void undo() { if (!done.isEmpty()) undone.addLast(done.removeLast()); }
    public void redo() { if (!undone.isEmpty()) done.addLast(undone.removeLast()); }
    public void clear() { done.clear(); undone.clear(); }

    public void clearPage(int pageIndex) {
        done.removeIf(s -> s.pageIndex == pageIndex);
        undone.clear();
    }

    public List<InkStroke> getStrokesForPage(int pageIndex) {
        List<InkStroke> out = new ArrayList<>();
        for (InkStroke s : done) if (s.pageIndex == pageIndex) out.add(s);
        return out;
    }

    public List<InkStroke> getAll() {
        return new ArrayList<>(done);
    }
}
