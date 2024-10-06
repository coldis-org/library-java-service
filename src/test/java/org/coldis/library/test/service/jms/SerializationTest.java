package org.coldis.library.test.service.jms;

import org.apache.fury.Fury;
import org.apache.fury.config.CompatibleMode;
import org.apache.fury.config.Language;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Serialization test.
 */
public class SerializationTest {

	/** Tests the DTO serialization. */
	@Test
	public void testDtoSerialization() throws Exception {
		final DtoTestObject testObject = new DtoTestObject(1L, "2", "3", 4, new int[] { 5 }, 6);

		final Fury objectSerializer = Fury.builder().registerGuavaTypes(false).withLanguage(Language.JAVA).withCompatibleMode(CompatibleMode.COMPATIBLE)
				.withClassVersionCheck(false).build();
		objectSerializer.register(DtoTestObject.class);
		final Fury dtoSerializer = Fury.builder().registerGuavaTypes(false).withLanguage(Language.JAVA).withCompatibleMode(CompatibleMode.COMPATIBLE)
				.withClassVersionCheck(false).withDeserializeNonexistentClass(true).build();
		dtoSerializer.register(DtoTestObjectDto.class);

		// Serializes using general object serializer and de-seralizes using DTO
		// serializer.
		final byte[] serializedObject = objectSerializer.serialize(testObject);
		final DtoTestObjectDto deserializedDto = (DtoTestObjectDto) dtoSerializer.deserialize(serializedObject);
		Assertions.assertEquals(testObject.getId(), deserializedDto.getId());

	}

}
