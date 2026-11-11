package eu.cessda.cmv.benchmark.service;

public class HarvestingFailedException extends Exception {
    public HarvestingFailedException(String message) {
        super(message);
    }

    public HarvestingFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
