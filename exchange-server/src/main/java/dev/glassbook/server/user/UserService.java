package dev.glassbook.server.user;

import dev.glassbook.core.model.Command;
import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.server.api.ApiException;
import dev.glassbook.server.config.GlassbookProperties;
import dev.glassbook.server.config.InstrumentCatalog;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final ExchangeCore core;
    private final InstrumentCatalog catalog;
    private final GlassbookProperties properties;

    public UserService(UserRepository users, PasswordEncoder encoder, ExchangeCore core, InstrumentCatalog catalog,
                       GlassbookProperties properties) {
        this.users = users;
        this.encoder = encoder;
        this.core = core;
        this.catalog = catalog;
        this.properties = properties;
    }

    public UserEntity create(String username, String rawPassword, Role role) {
        if (users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Такой username занят");
        }

        try {
            return users.saveAndFlush(new UserEntity(username, encoder.encode(rawPassword), role));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Такой username занят");
        }
    }

    public CompletableFuture<Void> grantWelcomeFunds(long participantId) {
        return grant(participantId, properties.demo().welcomeCash(), properties.demo().welcomeShares());
    }

    public CompletableFuture<Void> grant(long participantId, long cash, long sharesPerInstrument) {
        List<CompletableFuture<?>> pending = new ArrayList<>();
        if (cash > 0) {
            pending.add(core.submit(new Command.Deposit(participantId, 0, cash)));
        }

        if (sharesPerInstrument > 0) {
            for (var entry : catalog.entries()) {
                long lot = entry.instrument().lotSize();
                long quantity = (sharesPerInstrument / lot) * lot;
                if (quantity > 0) {
                    pending.add(core.submit(new Command.Deposit(participantId, entry.instrument().id(), quantity)));
                }
            }
        }
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]));
    }
}
