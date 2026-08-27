package com.checkon.member.common.storage.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.checkon.member.common.storage.ObjectStoragePort;
import com.checkon.member.common.storage.ObjectStorageUnavailableException;
import com.checkon.member.common.storage.StoredObjectMetadata;

/**
 * 로컬 파일 시스템 구현. 발행자가 올려 둔 PDF 를 <b>읽기만</b> 한다.
 *
 * <p>🔴 이 어댑터는 쓰지 않는다({@code write}·{@code delete} 가 없다). member 는 PDF 를
 * 만들지도 지우지도 않는다.</p>
 *
 * <p>🔴 <b>경로 탈출을 물리적으로 막는다.</b> {@code objectKey} 는 발행자가 넣은 DB 값이지만,
 * 값이 오염되면 {@code ../../etc/passwd} 로 root 밖을 읽을 수 있다. 정규화한 절대 경로가
 * root 아래인지 확인하고, 아니면 「부재」와 같은 예외를 낸다 — 사유를 구분해 알려주지 않는다.</p>
 */
public class LocalFileObjectStorageAdapter implements ObjectStoragePort {

	private static final int BUFFER_SIZE = 8192;
	private static final String PREFIX = "sha256:";

	private final Path root;

	public LocalFileObjectStorageAdapter(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	/**
	 * 🔴 <b>{@code Files.probeContentType} 을 쓰지 않는다.</b> 그 결과가 OS·JDK·설치된
	 * mime 데이터베이스에 따라 갈린다 — macOS 실측은 {@code application/pdf} 지만 최소
	 * 리눅스 컨테이너에서는 {@code null} 이 나올 수 있고, 그러면 <b>CI 에서만</b> 정상 발급이
	 * 503 이 된다. MB-42(OS 경로 구분자)와 같은 종류의 결함이라 미리 닫는다.
	 *
	 * <p>파일 시스템은 content type 을 <b>저장하지 않는다.</b> 그래서 저장소가 보고할 수 있는
	 * 유일한 결정적 값은 <b>키의 확장자에서 유도한 것</b>이다. 이 값은 「저장소가 아는 사실」이
	 * 아니라 「키가 주장하는 형식」이므로, 무결성의 최종 판정은 여전히 <b>checksum 재계산</b>이다.</p>
	 */
	@Override
	public StoredObjectMetadata head(String objectKey) {
		Path file = resolve(objectKey);
		try {
			return new StoredObjectMetadata(contentTypeOf(objectKey), Files.size(file));
		}
		catch (IOException error) {
			throw new ObjectStorageUnavailableException("head_failed", error);
		}
	}

	/** 🔴 결정적 규칙 하나뿐이다. 확장자가 pdf 가 아니면 octet-stream 이고, 호출자가 503 을 낸다. */
	private static String contentTypeOf(String objectKey) {
		return objectKey.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
			? "application/pdf" : "application/octet-stream";
	}

	@Override
	public InputStream open(String objectKey) {
		Path file = resolve(objectKey);
		try {
			return Files.newInputStream(file);
		}
		catch (IOException error) {
			throw new ObjectStorageUnavailableException("open_failed", error);
		}
	}

	@Override
	public String sha256(String objectKey, long maxVerifyBytes) {
		Path file = resolve(objectKey);
		long size;
		try {
			size = Files.size(file);
		}
		catch (IOException error) {
			throw new ObjectStorageUnavailableException("size_failed", error);
		}
		// 🔴 상한을 넘으면 검증을 건너뛰고 통과시키지 않는다. 발급 자체를 막는다.
		if (size > maxVerifyBytes) {
			throw new ObjectStorageUnavailableException("too_large_to_verify");
		}
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(file)) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = input.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}
		catch (IOException error) {
			throw new ObjectStorageUnavailableException("read_failed", error);
		}
		return PREFIX + HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 must be available", error);
		}
	}

	private Path resolve(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			throw new ObjectStorageUnavailableException("blank_key");
		}
		Path candidate = root.resolve(objectKey).normalize();
		if (!candidate.startsWith(root)) {
			throw new ObjectStorageUnavailableException("outside_root");
		}
		if (!Files.isRegularFile(candidate)) {
			throw new ObjectStorageUnavailableException("absent");
		}
		return candidate;
	}
}
