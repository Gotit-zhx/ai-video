package com.yingdao.aicommerce.video.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Paid submissions, not successful candidates. Never refunded by a timeout or a restart. */
final class AiVideoImageAttempts {
    static final int LIMIT = 2;
    private final ObjectMapper mapper;

    AiVideoImageAttempts(ObjectMapper mapper) { this.mapper = mapper; }

    record Attempt(String id, String inputHash, boolean rejected) {}
    record State(int spent, List<Attempt> attempts) {}
    record Reservation(Attempt attempt, boolean fresh) {}

    synchronized Reservation reserve(Path file, String inputHash, int historicalAttempts) {
        State state = read(file, historicalAttempts);
        for (int i = state.attempts().size() - 1; i >= 0; i--) {
            Attempt attempt = state.attempts().get(i);
            if (attempt.inputHash().equals(inputHash) && !attempt.rejected())
                return new Reservation(attempt, false);
        }
        if (state.spent() >= LIMIT) throw new IllegalStateException(
                "IMAGE_GENERATION_LIMIT: 此素材已达到最多 " + LIMIT + " 次生图上限；已停止新增生图，保留已有结果");
        Attempt attempt = new Attempt(UUID.randomUUID().toString(), inputHash, false);
        var attempts = new ArrayList<>(state.attempts());
        attempts.add(attempt);
        write(file, new State(state.spent() + 1, attempts));
        return new Reservation(attempt, true);
    }

    synchronized void rejected(Path file, String id) {
        State state = read(file, 0);
        write(file, new State(state.spent(), state.attempts().stream()
                .map(a -> a.id().equals(id) ? new Attempt(a.id(), a.inputHash(), true) : a).toList()));
    }

    boolean hasRequest(Path file) { return Files.isRegularFile(file); }

    private State read(Path file, int historicalAttempts) {
        try {
            if (!Files.exists(file)) return new State(Math.max(0, historicalAttempts), List.of());
            State state = mapper.readValue(Files.readAllBytes(file), State.class);
            if (state.spent() < 0 || state.attempts() == null || state.spent() < state.attempts().size())
                throw new IllegalStateException("invalid ledger");
            return state;
        } catch (Exception error) {
            throw new IllegalStateException("IMAGE_GENERATION_LIMIT: 生图次数记录无法核对，已停止新增生图", error);
        }
    }

    private void write(Path file, State state) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".part");
            Files.write(temporary, mapper.writeValueAsBytes(state));
            // This ledger lives on the same local volume as candidates. Failure is fail-closed.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception error) {
            throw new IllegalStateException("IMAGE_GENERATION_LIMIT: 无法保存生图次数，未提交新请求", error);
        }
    }
}
