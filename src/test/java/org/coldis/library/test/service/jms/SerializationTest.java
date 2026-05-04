package org.coldis.library.test.service.jms;

import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
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

		final Fory objectSerializer = Fory.builder().registerGuavaTypes(false).withLanguage(Language.JAVA).withCompatibleMode(CompatibleMode.COMPATIBLE)
				.withClassVersionCheck(false).build();
		objectSerializer.register(DtoTestObject.class);
		final Fory dtoSerializer = Fory.builder().registerGuavaTypes(false).withLanguage(Language.JAVA).withCompatibleMode(CompatibleMode.COMPATIBLE)
				.withClassVersionCheck(false).withDeserializeUnknownClass(true).build();
		dtoSerializer.register(DtoTestObjectDto.class);

		// Serializes using general object serializer and de-seralizes using DTO
		// serializer.
		final byte[] serializedObject = objectSerializer.serialize(testObject);
		final DtoTestObjectDto deserializedDto = (DtoTestObjectDto) dtoSerializer.deserialize(serializedObject);
		Assertions.assertEquals(testObject.getId(), deserializedDto.getId());

	}

}
