package org.citriage.github;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Strict UTF-8 JSON and typed field checks for the REST responses and bundle manifest. */
final class JsonFields {
    private JsonFields() {
    }

    static JsonObject read(byte[] bytes) throws IOException {
        try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder()))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject value = object(readValue(reader));
            if (reader.peek() == JsonToken.END_DOCUMENT) {
                return value;
            }
        } catch (IllegalStateException | NumberFormatException exception) {
            throw new IOException("Invalid JSON.", exception);
        }
        throw new IOException("Unexpected data after the JSON document.");
    }

    private static JsonElement readValue(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readObject(reader);
            case BEGIN_ARRAY -> {
                reader.beginArray();
                var array = new JsonArray();
                while (reader.hasNext()) {
                    array.add(readValue(reader));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 100) {
                    throw new IOException("JSON number is too long.");
                }
                yield new JsonPrimitive(new BigDecimal(number));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Invalid JSON value.");
        };
    }

    private static JsonObject readObject(JsonReader reader) throws IOException {
        reader.beginObject();
        var object = new JsonObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (object.has(name)) {
                throw new IOException("Duplicate JSON field.");
            }
            object.add(name, readValue(reader));
        }
        reader.endObject();
        return object;
    }

    static JsonObject object(JsonElement value) throws IOException {
        if (value != null && value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        throw new IOException("Expected a JSON object.");
    }

    static JsonArray array(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value != null && value.isJsonArray()) {
            return value.getAsJsonArray();
        }
        throw invalid(key);
    }

    static String text(JsonObject object, String key) throws IOException {
        return optionalText(object, key).orElseThrow(() -> invalid(key));
    }

    static Optional<String> optionalText(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (absent(value)) {
            return Optional.empty();
        }
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            String text = primitive.getAsString();
            if (!text.isBlank() && text.length() <= 4096 && text.chars().noneMatch(Character::isISOControl)) {
                return Optional.of(text);
            }
        }
        throw invalid(key);
    }

    static long number(JsonObject object, String key, long minimum) throws IOException {
        JsonElement value = object.get(key);
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
            String text = primitive.getAsString();
            if (text.matches("[0-9]{1,19}")) {
                try {
                    long number = Long.parseLong(text);
                    if (number >= minimum) {
                        return number;
                    }
                } catch (NumberFormatException ignored) {
                    // Report the invalid field without printing the supplied value.
                }
            }
        }
        throw invalid(key);
    }

    static OptionalLong optionalId(JsonObject object, String key) throws IOException {
        return absent(object.get(key)) ? OptionalLong.empty() : OptionalLong.of(number(object, key, 1));
    }

    static int attempt(JsonObject object, String key) throws IOException {
        long value = number(object, key, 1);
        if (value > Integer.MAX_VALUE) {
            throw invalid(key);
        }
        return (int) value;
    }

    static boolean bool(JsonObject object, String key) throws IOException {
        if (object.get(key) instanceof JsonPrimitive primitive && primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        throw invalid(key);
    }

    static Optional<Instant> timestamp(JsonObject object, String key) throws IOException {
        try {
            return optionalText(object, key).map(text -> OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException exception) {
            throw invalid(key);
        }
    }

    static void onlyKeys(JsonObject object, String... names) throws IOException {
        if (!Set.of(names).containsAll(object.keySet())) {
            throw new IOException("The bundle manifest contains unsupported fields.");
        }
    }

    static boolean absent(JsonElement value) {
        return value == null || value.isJsonNull();
    }

    private static IOException invalid(String key) {
        return new IOException("Missing or invalid '" + key + "' field.");
    }
}
