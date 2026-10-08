package eu.cessda.cmv.benchmark;

import java.io.Serial;
import java.net.URI;

import static eu.cessda.cmv.benchmark.RunBenchmarkAssessment.PROCFAIL;

public class ProcessingException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = -3405200566636731544L;

    public ProcessingException(URI guid, Throwable cause) {
        super(PROCFAIL + guid + ": " + cause.toString(), cause);
    }
}
