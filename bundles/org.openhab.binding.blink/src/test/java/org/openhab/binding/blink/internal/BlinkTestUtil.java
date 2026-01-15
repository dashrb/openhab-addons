/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.blink.internal;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.blink.internal.dto.BlinkAccount;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.json.internal.JsonStorage;
import org.openhab.core.storage.json.internal.migration.TypeMigrator;

/**
 * Test class.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
@NonNullByDefault
public class BlinkTestUtil {

    public static BlinkAccount testBlinkAccount() {
        BlinkAccount blinkAccount = new BlinkAccount();
        blinkAccount.account = new BlinkAccount.Account();
        blinkAccount.account.account_id = "123";
        blinkAccount.account.tier = "e006";
        blinkAccount.auth = new BlinkAccount.Auth();
        blinkAccount.auth.access_token = "abc";
        blinkAccount.auth.refresh_token = "def";
        blinkAccount.auth.expires_in = 86400;
        blinkAccount.auth.tokenExpiresAt = Instant.now().plus(86400, ChronoUnit.SECONDS);
        blinkAccount.lastTokenRefresh = Instant.now();
        return blinkAccount;
    }

    public static BlinkAccount testUnauthenticatedBlinkAccount() {
        BlinkAccount blinkAccount = new BlinkAccount();
        blinkAccount.account = new BlinkAccount.Account();
        blinkAccount.account.account_id = "123";
        blinkAccount.account.tier = "e006";
        blinkAccount.auth = null;
        blinkAccount.lastTokenRefresh = Instant.EPOCH;
        return blinkAccount;
    }

    public static Storage<Object> testStorage() {
        Storage<Object> storage = new JsonStorage<Object>(new File("unused tmp file"), String.class.getClassLoader(), 0,
                0, 0, new LinkedList<TypeMigrator>());
        return storage;
    }
}
