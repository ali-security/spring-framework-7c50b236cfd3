/*
 * Copyright 2002-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.support;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.ThrowableTypeAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.core.SpringProperties;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests for {@link SimpleEvaluationContext}.
 *
 * @author Sam Brannen
 */
class SimpleEvaluationContextTests {

	private static final List<String> conversions = new ArrayList<>();

	private final SpelExpressionParser parser = new SpelExpressionParser();

	private final Catalog catalog = new Catalog();


	@BeforeEach
	void clearConversions() {
		conversions.clear();
	}

	@Test
	void compilationNotSupportedByDefault() {
		assertThat(SimpleEvaluationContext.forReadOnlyDataBinding().build().isCompilationSupported()).isFalse();
		assertThat(SimpleEvaluationContext.forReadWriteDataBinding().build().isCompilationSupported()).isFalse();
		assertThat(SimpleEvaluationContext.forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess())
				.build().isCompilationSupported()).isFalse();
	}

	@Test
	void compilationSupportedViaBuilder() {
		assertThat(SimpleEvaluationContext.forReadOnlyDataBinding()
				.withCompilationSupported().build().isCompilationSupported()).isTrue();
		assertThat(SimpleEvaluationContext.forReadWriteDataBinding()
				.withCompilationSupported().build().isCompilationSupported()).isTrue();
		assertThat(SimpleEvaluationContext.forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess())
				.withCompilationSupported().build().isCompilationSupported()).isTrue();
	}

	@Test
	void mapKeyConversionViaConstructorNotSupportedByDefault() {
		SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();

		assertThatSpelEvaluationException()
				.isThrownBy(() -> parser.parseExpression("skus['ABC-123']").getValue(context, catalog))
				.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.TYPE_CONVERSION_ERROR))
				.withRootCauseInstanceOf(ConverterNotFoundException.class);
		assertThat(conversions).isEmpty();
	}

	@Test
	void propertyConversionViaConstructorNotSupportedByDefault() {
		SimpleEvaluationContext context = SimpleEvaluationContext.forReadWriteDataBinding().build();

		assertThatSpelEvaluationException()
				.isThrownBy(() -> parser.parseExpression("sku = 'ABC-123'").getValue(context, catalog))
				.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.EXCEPTION_DURING_PROPERTY_WRITE))
				.withRootCauseInstanceOf(ConverterNotFoundException.class);
		assertThat(conversions).isEmpty();
	}

	@Test
	void methodArgumentConversionViaConstructorNotSupportedByDefault() {
		SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
				.withInstanceMethods()
				.build();

		assertThatSpelEvaluationException()
				.isThrownBy(() -> parser.parseExpression("contains('ABC-123')").getValue(context, catalog))
				.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.METHOD_NOT_FOUND));
		assertThat(conversions).isEmpty();
	}

	/**
	 * Each conversion is not supported with the default {@code ConversionService}
	 * but is supported with a standard {@link DefaultConversionService}.
	 */
	@ParameterizedTest
	@CsvSource(quoteCharacter = '"', delimiterString = "->", textBlock = """
		skus['ABC-123']     -> new Sku(ABC-123)
		sku = 'ABC-123'     -> new Sku(ABC-123)
		contains('ABC-123') -> new Sku(ABC-123)
		isbns['ABC-123']    -> Isbn.valueOf(ABC-123)
		products['ABC-123'] -> Product.findProduct(ABC-123)
		listing = draft     -> Draft.toListing()
		""")
	void reflectiveConversionNotSupportedByDefault(String expression, String conversion) {
		SimpleEvaluationContext defaultContext = SimpleEvaluationContext.forReadWriteDataBinding()
				.withInstanceMethods()
				.build();

		assertThatSpelEvaluationException()
				.isThrownBy(() -> parser.parseExpression(expression).getValue(defaultContext, catalog));
		assertThat(conversions).isEmpty();

		SimpleEvaluationContext customContext = SimpleEvaluationContext.forReadWriteDataBinding()
				.withInstanceMethods()
				.withConversionService(new DefaultConversionService())
				.build();

		parser.parseExpression(expression).getValue(customContext, catalog);
		assertThat(conversions).containsExactly(conversion);
	}

	@Test
	void customTypeConverterIsUsedAsIs() {
		SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
				.withTypeConverter(new StandardTypeConverter())
				.build();

		assertThat(parser.parseExpression("skus['ABC-123']").getValue(context, catalog)).isNull();
		assertThat(conversions).containsExactly("new Sku(ABC-123)");
	}

	@Test
	void commonConversionsSupportedByDefault() {
		SimpleEvaluationContext context = SimpleEvaluationContext.forReadWriteDataBinding().build();
		ConversionModel conversionModel = new ConversionModel();

		parser.parseExpression("number = '42'").getValue(context, conversionModel);
		assertThat(conversionModel.getNumber()).isEqualTo(42);

		parser.parseExpression("flag = 'true'").getValue(context, conversionModel);
		assertThat(conversionModel.getFlag()).isTrue();

		parser.parseExpression("booleans[0]").setValue(context, conversionModel, "false");
		assertThat(conversionModel.getBooleans()).containsExactly(false);

		parser.parseExpression("color = 'BLUE'").getValue(context, conversionModel);
		assertThat(conversionModel.getColor()).isEqualTo(Color.BLUE);

		UUID uuid = UUID.randomUUID();
		parser.parseExpression("uuid = '" + uuid + "'").getValue(context, conversionModel);
		assertThat(conversionModel.getUuid()).isEqualTo(uuid);

		// text and builder are both properties on the root context object.
		parser.parseExpression("text = builder").getValue(context, conversionModel);
		assertThat(conversionModel.getText()).isEqualTo("built");

		assertThat(parser.parseExpression("numberKeys['1']").getValue(context, conversionModel)).isEqualTo("one");
		assertThat(parser.parseExpression("colorKeys['RED']").getValue(context, conversionModel)).isEqualTo("red");
		assertThat(parser.parseExpression("colorKeys[RED]").getValue(context, conversionModel)).isEqualTo("red");
	}

	@Test
	void withConverter() {
		ConversionModel conversionModel = new ConversionModel();
		SimpleEvaluationContext defaultContext = SimpleEvaluationContext.forReadWriteDataBinding().build();

		assertThatSpelEvaluationException()
				.isThrownBy(() -> parser.parseExpression("zoneId = 'Europe/Paris'").getValue(defaultContext, conversionModel))
				.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.EXCEPTION_DURING_PROPERTY_WRITE))
				.withRootCauseInstanceOf(ConverterNotFoundException.class);

		SimpleEvaluationContext context = SimpleEvaluationContext.forReadWriteDataBinding()
				.withConverter(String.class, ZoneId.class, ZoneId::of)
				.build();

		parser.parseExpression("zoneId = 'Europe/Paris'").getValue(context, conversionModel);
		assertThat(conversionModel.getZoneId()).isEqualTo(ZoneId.of("Europe/Paris"));

		// Common conversions are still supported.
		parser.parseExpression("number = '42'").getValue(context, conversionModel);
		assertThat(conversionModel.getNumber()).isEqualTo(42);

		// Reflective conversions are still not supported.
		assertThatSpelEvaluationException()
				.isThrownBy(() -> parser.parseExpression("skus['ABC-123']").getValue(context, catalog))
				.withRootCauseInstanceOf(ConverterNotFoundException.class);
		assertThat(conversions).isEmpty();
	}

	@Test
	void withConverterCannotBeCombinedWithCustomConversionServiceOrTypeConverter() {
		assertThatIllegalStateException().isThrownBy(() -> SimpleEvaluationContext.forReadOnlyDataBinding()
				.withConverter(String.class, ZoneId.class, ZoneId::of)
				.withConversionService(new DefaultConversionService())
				.build());

		assertThatIllegalStateException().isThrownBy(() -> SimpleEvaluationContext.forReadOnlyDataBinding()
				.withTypeConverter(new StandardTypeConverter())
				.withConverter(String.class, ZoneId.class, ZoneId::of)
				.build());
	}

	@Test
	void reflectiveConversionProperty() {
		SpringProperties.setFlag(SimpleEvaluationContext.REFLECTIVE_CONVERSION_PROPERTY_NAME);
		try {
			SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();

			assertThat(parser.parseExpression("skus['ABC-123']").getValue(context, catalog)).isNull();
			assertThat(conversions).containsExactly("new Sku(ABC-123)");

			conversions.clear();
			ConversionModel conversionModel = new ConversionModel();
			context = SimpleEvaluationContext.forReadWriteDataBinding()
					.withConverter(String.class, ZoneId.class, ZoneId::of)
					.build();

			parser.parseExpression("zoneId = 'Europe/Paris'").getValue(context, conversionModel);
			assertThat(conversionModel.getZoneId()).isEqualTo(ZoneId.of("Europe/Paris"));
			assertThat(parser.parseExpression("skus['ABC-123']").getValue(context, catalog)).isNull();
			assertThat(conversions).containsExactly("new Sku(ABC-123)");
		}
		finally {
			SpringProperties.setProperty(SimpleEvaluationContext.REFLECTIVE_CONVERSION_PROPERTY_NAME, null);
		}
	}


	private static ThrowableTypeAssert<SpelEvaluationException> assertThatSpelEvaluationException() {
		return assertThatExceptionOfType(SpelEvaluationException.class);
	}


	static class Catalog {

		private final Map<Sku, String> skus = new HashMap<>();
		private final Map<Isbn, String> isbns = new HashMap<>();
		private final Map<Product, String> products = new HashMap<>();
		private Sku sku;
		private final Draft draft = new Draft();
		private Listing listing;

		public Map<Sku, String> getSkus() {
			return this.skus;
		}

		public Map<Isbn, String> getIsbns() {
			return this.isbns;
		}

		public Map<Product, String> getProducts() {
			return this.products;
		}

		public Sku getSku() {
			return this.sku;
		}

		public void setSku(Sku sku) {
			this.sku = sku;
		}

		public Draft getDraft() {
			return this.draft;
		}

		public Listing getListing() {
			return this.listing;
		}

		public void setListing(Listing listing) {
			this.listing = listing;
		}

		public boolean contains(Sku sku) {
			return this.skus.containsKey(sku);
		}
	}

	static class Sku {

		public Sku(String code) {
			conversions.add("new Sku(" + code + ")");
		}
	}

	static class Isbn {

		private Isbn() {
		}

		public static Isbn valueOf(String code) {
			conversions.add("Isbn.valueOf(" + code + ")");
			return new Isbn();
		}
	}

	// Must be public, since IdToEntityConverter does not make the finder method accessible.
	public static class Product {

		private Product() {
		}

		public static Product findProduct(String id) {
			conversions.add("Product.findProduct(" + id + ")");
			return new Product();
		}
	}

	static class Draft {

		public Listing toListing() {
			conversions.add("Draft.toListing()");
			return new Listing();
		}
	}

	static class Listing {
	}

	enum Color {
		RED, BLUE
	}

	static class ConversionModel {

		private Integer number;
		private Boolean flag;
		private final List<Boolean> booleans = new ArrayList<>();
		private Color color;
		private UUID uuid;
		private ZoneId zoneId;
		private String text;
		private final StringBuilder builder = new StringBuilder("built");
		private final Map<Integer, String> numberKeys = new HashMap<>();
		private final Map<Color, String> colorKeys = new HashMap<>();

		ConversionModel() {
			this.booleans.add(true);
			this.numberKeys.put(1, "one");
			this.colorKeys.put(Color.RED, "red");
		}

		public Integer getNumber() {
			return this.number;
		}

		public void setNumber(Integer number) {
			this.number = number;
		}

		public Boolean getFlag() {
			return this.flag;
		}

		public void setFlag(Boolean flag) {
			this.flag = flag;
		}

		public List<Boolean> getBooleans() {
			return this.booleans;
		}

		public Color getColor() {
			return this.color;
		}

		public void setColor(Color color) {
			this.color = color;
		}

		public UUID getUuid() {
			return this.uuid;
		}

		public void setUuid(UUID uuid) {
			this.uuid = uuid;
		}

		public ZoneId getZoneId() {
			return this.zoneId;
		}

		public void setZoneId(ZoneId zoneId) {
			this.zoneId = zoneId;
		}

		public String getText() {
			return this.text;
		}

		public void setText(String text) {
			this.text = text;
		}

		public StringBuilder getBuilder() {
			return this.builder;
		}

		public Map<Integer, String> getNumberKeys() {
			return this.numberKeys;
		}

		public Map<Color, String> getColorKeys() {
			return this.colorKeys;
		}
	}

}
