package eu.cessda.cmv.benchmark.models;

public record CachedRecord(long mtime, long size, SlimRecord slim) {
}
