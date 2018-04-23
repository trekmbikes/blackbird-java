package com.slickapps.blackbird.data;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collection;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.slickapps.blackbird.exchanges.BlackbirdExchange;
import com.slickapps.blackbird.model.ExchangePairsInMarket;

public class SaveFileDAO {

	private static final ObjectMapper OBJECT_MAPPER = new CustomJsonObjectMapper();

	public static ExchangePairsInMarket fileImport(Collection<? extends BlackbirdExchange> exchanges, File file)
			throws IOException {
		ExchangePairsInMarket results = OBJECT_MAPPER.readValue(file, ExchangePairsInMarket.class);
		results.filterExchanges(exchanges);
		results.resetVersions();
		return results;
	}

	public static void fileExport(File file, ExchangePairsInMarket exchangePairsInMarket) throws IOException {
		if (exchangePairsInMarket.getNumPairsInMarket() != 0) {
			OBJECT_MAPPER.writeValue(file, exchangePairsInMarket);
		} else {
			if (file.exists())
				file.delete();
		}
	}

	static class CustomJsonObjectMapper extends ObjectMapper {
		private static final long serialVersionUID = 3889851622287778724L;

		/*
		 * Could have instead used https://github.com/FasterXML/jackson-datatype-jsr310
		 * but this allows us to standardize on our DateUtil formats and introduces one
		 * less library - CPB
		 */
		public CustomJsonObjectMapper() {
			SimpleModule module = new SimpleModule("JSONModule", new Version(2, 0, 0, null, null, null));
			
			module.addSerializer(LocalDateTime.class, new StdSerializer<LocalDateTime>(LocalDateTime.class) {
				private static final long serialVersionUID = -805229080847366922L;

				@Override
				public void serialize(LocalDateTime value, JsonGenerator jgen, SerializerProvider provider)
						throws IOException, JsonGenerationException {
					jgen.writeString(value.toString());
				}
			});
			module.addDeserializer(LocalDateTime.class, new StdDeserializer<LocalDateTime>(LocalDateTime.class) {
				private static final long serialVersionUID = -3290548677012921823L;

				@Override
				public LocalDateTime deserialize(JsonParser jp, DeserializationContext ctxt)
						throws IOException, JsonProcessingException {
					return LocalDateTime.parse(jp.getValueAsString());
				}
			});

			registerModule(module);
			enable(SerializationFeature.INDENT_OUTPUT);
		}
	}

}
