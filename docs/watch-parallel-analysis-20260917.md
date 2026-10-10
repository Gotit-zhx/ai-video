# Parallel watch video analysis

Failure: task 594e1652-c4a0-43e4-b9df-0cb9072cc7c3 read all 11 frames, then reasoned for 15 minutes without visible text and hit CHAT_TIMEOUT.

The new flow retains the original watch frame-selection script. Up to three Pi AgentHarness sessions analyze overlapping frame batches, returning compact timestamped evidence. The final Pi/watch session synthesizes nine shots using the evidence and original images. The compact platform skill retains the original standalone documentation in UPSTREAM.md.

Watch classifies non-photographic platform end cards, search screens, and pure text cards as unusable. These timestamps cannot be selected for the storyboard. Filmed scenes with overlaid subtitles remain usable, without copying their text. Validation checks exact frame coverage, chronological source timestamps, first/last usable frames, production timeline, narration provenance, and shot count. Failed analyses are not automatically replayed.

Limits: at most three concurrent batches per task; three minutes per evidence batch, four minutes for synthesis including corrections, ten minutes overall.

Initial real-model validation: 217164ms, eight model calls, three evidence batches, nine shots, 7899 characters. Visual review found an unwanted platform end card; exclusion was subsequently implemented. This initial run is not the acceptance result for the later exclusion change.

41 runtime tests and 10 frontend tests pass. Final live task 84af5c92-a7c0-48ab-a14f-54b3a2bff7ae completed READY: runtime 225318ms, nine saved shots and nine nonempty JPEG previews, 6563 output characters. Source timestamps [0,2.3,3.43,4.67,5.77,6.83,8.4,9.43,10.63] strictly increase. Platform end-card timestamp 11.7 is excluded; positive shot fields contain no platform-search interface; all narration is empty. See output/watch-parallel-20260917/final-live-validation.json. This is evidence from one video, not a general latency or accuracy guarantee.
