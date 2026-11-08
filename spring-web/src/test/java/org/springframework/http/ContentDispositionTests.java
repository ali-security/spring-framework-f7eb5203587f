/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.http;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.http.ContentDisposition.builder;

/**
 * Unit tests for {@link ContentDisposition}
 * @author Sebastien Deleuze
 * @author Rossen Stoyanchev
 */
public class ContentDispositionTests {

	private static DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;


	@Test
	public void parse() {
		assertThat(parse("form-data; name=\"foo\"; filename=\"foo.txt\"; size=123"))
				.isEqualTo(builder("form-data")
						.name("foo")
						.filename("foo.txt")
						.size(123L)
						.build());
	}

	@Test
	public void parseFilenameUnquoted() {
		assertThat(parse("form-data; filename=unquoted"))
				.isEqualTo(builder("form-data")
						.filename("unquoted")
						.build());
	}

	@Test  // SPR-16091
	public void parseFilenameWithSemicolon() {
		assertThat(parse("attachment; filename=\"filename with ; semicolon.txt\""))
				.isEqualTo(builder("attachment")
						.filename("filename with ; semicolon.txt")
						.build());
	}

	@Test
	public void parseEncodedFilename() {
		assertThat(parse("form-data; name=\"name\"; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt"))
				.isEqualTo(builder("form-data")
						.name("name")
						.filename("中文.txt", StandardCharsets.UTF_8)
						.build());
	}

	@Test // gh-24112
	public void parseEncodedFilenameWithPaddedCharset() {
		assertThat(parse("attachment; filename*= UTF-8''some-file.zip"))
				.isEqualTo(builder("attachment")
						.filename("some-file.zip", StandardCharsets.UTF_8)
						.build());
	}

	@Test
	public void parseEncodedFilenameWithoutCharset() {
		assertThat(parse("form-data; name=\"name\"; filename*=test.txt"))
				.isEqualTo(builder("form-data")
						.name("name")
						.filename("test.txt")
						.build());
	}

	@Test
	public void parseEncodedFilenameWithInvalidCharset() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-16''test.txt"));
	}

	@Test
	public void parseEncodedFilenameWithInvalidName() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-8''%A"));

		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("form-data; name=\"name\"; filename*=UTF-8''%A.txt"));
	}

	@Test // gh-23077
	public void parseWithEscapedQuote() {

		BiConsumer<String, String> tester = (description, filename) ->
			assertThat(parse("form-data; name=\"file\"; filename=\"" + filename + "\"; size=123"))
					.as(description)
					.isEqualTo(builder("form-data").name("file").filename(filename).size(123L).build());

		tester.accept("Escaped quotes should be ignored",
				"\\\"The Twilight Zone\\\".txt");

		tester.accept("Escaped quotes preceded by escaped backslashes should be ignored",
				"\\\\\\\"The Twilight Zone\\\\\\\".txt");

		tester.accept("Escaped backslashes should not suppress quote",
				"The Twilight Zone \\\\");

		tester.accept("Escaped backslashes should not suppress quote",
				"The Twilight Zone \\\\\\\\");
	}

	@Test
	public void parseWithExtraSemicolons() {
		assertThat(parse("form-data; name=\"foo\";; ; filename=\"foo.txt\"; size=123"))
				.isEqualTo(builder("form-data")
						.name("foo")
						.filename("foo.txt")
						.size(123L)
						.build());
	}

	@Test
	public void parseDates() {
		ZonedDateTime creationTime = ZonedDateTime.parse("Mon, 12 Feb 2007 10:15:30 -0500", formatter);
		ZonedDateTime modificationTime = ZonedDateTime.parse("Tue, 13 Feb 2007 10:15:30 -0500", formatter);
		ZonedDateTime readTime = ZonedDateTime.parse("Wed, 14 Feb 2007 10:15:30 -0500", formatter);

		assertThat(
				parse("attachment; " +
						"creation-date=\"" + creationTime.format(formatter) + "\"; " +
						"modification-date=\"" + modificationTime.format(formatter) + "\"; " +
						"read-date=\"" + readTime.format(formatter) + "\"")).isEqualTo(
				builder("attachment")
						.creationDate(creationTime)
						.modificationDate(modificationTime)
						.readDate(readTime)
						.build());
	}

	@Test
	public void parseIgnoresInvalidDates() {
		ZonedDateTime readTime = ZonedDateTime.parse("Wed, 14 Feb 2007 10:15:30 -0500", formatter);

		assertThat(
				parse("attachment; " +
						"creation-date=\"-1\"; " +
						"modification-date=\"-1\"; " +
						"read-date=\"" + readTime.format(formatter) + "\"")).isEqualTo(
				builder("attachment")
						.readDate(readTime)
						.build());
	}

	@Test
	public void parseEmpty() {
		assertThatIllegalArgumentException().isThrownBy(() -> parse(""));
	}

	@Test
	public void parseNoType() {
		assertThatIllegalArgumentException().isThrownBy(() -> parse(";"));
	}

	@Test
	public void parseInvalidParameter() {
		assertThatIllegalArgumentException().isThrownBy(() -> parse("foo;bar"));
	}

	private static ContentDisposition parse(String input) {
		return ContentDisposition.parse(input);
	}


	@Test
	public void format() {
		assertThat(
				builder("form-data")
						.name("foo")
						.filename("foo.txt")
						.size(123L)
						.build().toString())
				.isEqualTo("form-data; name=\"foo\"; filename=\"foo.txt\"; size=123");
	}

	@Test
	public void formatWithEncodedFilename() {
		assertThat(
				builder("form-data")
						.name("name")
						.filename("中文.txt", StandardCharsets.UTF_8)
						.build().toString())
				.isEqualTo("form-data; name=\"name\"; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt");
	}

	@Test
	public void formatWithEncodedFilenameUsingUsAscii() {
		assertThat(
				builder("form-data")
						.name("name")
						.filename("test.txt", StandardCharsets.US_ASCII)
						.build()
						.toString())
				.isEqualTo("form-data; name=\"name\"; filename=\"test.txt\"");
	}

	@Test // gh-24220
	public void formatWithFilenameWithQuotes() {

		BiConsumer<String, String> tester = (input, output) -> {

			assertThat(builder("form-data").filename(input).build().toString())
					.isEqualTo("form-data; filename=\"" + output + "\"");

			assertThat(builder("form-data").filename(input, StandardCharsets.US_ASCII).build().toString())
					.isEqualTo("form-data; filename=\"" + output + "\"");
		};

		String filename = "\"foo.txt";
		tester.accept(filename, "\\" + filename);

		filename = "\\\"foo.txt";
		tester.accept(filename, filename);

		filename = "\\\\\"foo.txt";
		tester.accept(filename, "\\" + filename);

		filename = "\\\\\\\"foo.txt";
		tester.accept(filename, filename);

		filename = "\\\\\\\\\"foo.txt";
		tester.accept(filename, "\\" + filename);

		tester.accept("\"\"foo.txt", "\\\"\\\"foo.txt");
		tester.accept("\"\"\"foo.txt", "\\\"\\\"\\\"foo.txt");

		tester.accept("foo.txt\\", "foo.txt");
		tester.accept("foo.txt\\\\", "foo.txt\\\\");
		tester.accept("foo.txt\\\\\\", "foo.txt\\\\");
	}

	@Test
	public void formatWithEncodedFilenameUsingInvalidCharset() {
		assertThatIllegalArgumentException().isThrownBy(() ->
				builder("form-data")
						.name("name")
						.filename("test.txt", StandardCharsets.UTF_16)
						.build()
						.toString());
	}

	@Test
	public void formatWithNameWithControlCharacters() {
		String header = builder("form-data")
				.name("foo\r\nX-Injected: evil")
				.build()
				.toString();

		assertThat(header).isEqualTo("form-data; name=\"fooX-Injected: evil\"");
		assertThat(header).doesNotContain("\r", "\n");
	}

	@Test
	public void formatWithFilenameWithControlCharacters() {
		String header = builder("attachment")
				.filename("foo.txt\r\nX-Injected: evil")
				.build()
				.toString();

		assertThat(header).isEqualTo("attachment; filename=\"foo.txtX-Injected: evil\"");
		assertThat(header).doesNotContain("\r", "\n");
	}

	@Test
	public void formatWithFilenameWithControlCharactersUsingUsAscii() {
		String header = builder("attachment")
				.filename("foo.txt\r\nX-Injected: evil", StandardCharsets.US_ASCII)
				.build()
				.toString();

		assertThat(header).isEqualTo("attachment; filename=\"foo.txtX-Injected: evil\"");
		assertThat(header).doesNotContain("\r", "\n");
	}

	@Test
	public void formatStripsAllControlCharacters() {
		StringBuilder controlChars = new StringBuilder();
		for (int i = 0x00; i <= 0x1F; i++) {
			controlChars.append((char) i);
		}
		controlChars.append((char) 0x7F);
		String value = "a" + controlChars + "b";

		assertThat(builder("form-data").name(value).filename(value).build().toString())
				.isEqualTo("form-data; name=\"ab\"; filename=\"ab\"");
	}

	@Test
	public void formatWithFilenameEndingInBackslashFollowedByControlCharacters() {
		// Control characters must be stripped *before* quotation marks are escaped:
		// otherwise the exposed trailing backslash escapes the closing quote.
		String header = builder("attachment")
				.filename("foo.txt\\\r\n")
				.build()
				.toString();

		assertThat(header).isEqualTo("attachment; filename=\"foo.txt\"");
		assertThat(header).doesNotContain("\r", "\n");
	}

	@Test
	public void formatWithNameEndingInBackslashFollowedByControlCharacters() {
		String header = builder("form-data")
				.name("foo\\\r\n")
				.filename("foo.txt")
				.build()
				.toString();

		assertThat(header).isEqualTo("form-data; name=\"foo\"; filename=\"foo.txt\"");
		assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo("foo.txt");
	}

	@Test
	public void formatWithNameWithQuotes() {
		String header = builder("form-data")
				.name("foo\"; filename=\"evil.sh")
				.build()
				.toString();

		assertThat(header).isEqualTo("form-data; name=\"foo\\\"; filename=\\\"evil.sh\"");
		assertThat(ContentDisposition.parse(header).getFilename()).isNull();
	}

	@Test
	public void formatWithEncodedFilenameWithControlCharacters() {
		String header = builder("attachment")
				.filename("foo\r\n.txt", StandardCharsets.UTF_8)
				.build()
				.toString();

		assertThat(header).isEqualTo("attachment; filename*=UTF-8''foo%0D%0A.txt");
		assertThat(header).doesNotContain("\r", "\n");
	}

}
