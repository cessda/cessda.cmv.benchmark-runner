package cessda.cmv.benchmark;

import java.io.IOException;
import java.io.Serial;
import java.util.List;

public final class OverwhelmedIndicatorException extends IOException {
    @Serial
    private static final long serialVersionUID = -5811152786087330809L;

    private final List<String> indicators;

    public OverwhelmedIndicatorException(String guid, List<String> indicators) {
        super("Champion response for GUID " + guid
                + " contained overwhelmed indicator(s): "
                + String.join(", ", indicators));
        this.indicators = List.copyOf(indicators);
    }

    public List<String> getIndicators() {
        return indicators;
    }
}
