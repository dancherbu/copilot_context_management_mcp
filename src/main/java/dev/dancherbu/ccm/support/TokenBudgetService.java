package dev.dancherbu.ccm.support;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Service;

@Service
public class TokenBudgetService {

    private final Encoding encoding;

    public TokenBudgetService() {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int countTokens(String value) {
        return encoding.countTokens(value == null ? "" : value);
    }

    public boolean exceeds(String value, int limit) {
        return countTokens(value) > limit;
    }
}
