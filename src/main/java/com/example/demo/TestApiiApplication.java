package com.example.demo;
import com.google.common.util.concurrent.RateLimiter;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Null;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import org.hibernate.annotations.CreationTimestamp;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@EnableRetry
@SpringBootApplication
public class TestApiiApplication {
	public static void main(String[] args) {
		SpringApplication.run(TestApiiApplication.class, args);
	}

	@Data
	@Component
	static class ThreadLimiter {
		@Value("${spring.concurrency.limiter.request-limit}")
		private int requestLimit;
		@Value("${spring.concurrency.limiter.warmup-period}")
		private int warmupPeriod;
		private TimeUnit timeUnit;

		private RateLimiter rateLimiter;

		@Autowired
		public ThreadLimiter(@Value("${spring.concurrency.limiter.time-unit}") String timeUnit) {
			this.timeUnit = TimeUnit.valueOf(timeUnit);
		}

		@PostConstruct
		private void init() {
			rateLimiter = RateLimiter.create(requestLimit, warmupPeriod, this.timeUnit);
		}

		public boolean checkAvailableThread() {
			return rateLimiter.tryAcquire();
		}
	}

	@RestController
	@RequiredArgsConstructor
	@RequestMapping("/api/v3/lk/documents")
	static class DocumentController {
		private final DocumentService documentService;

		@PostMapping("/create")
		public DocumentDto createDocument(@RequestBody @Valid DocumentDto documentDto) {
			return documentService.createDocument(documentDto);
		}
	}

	@Service
	@RequiredArgsConstructor
	static class DocumentService {
		private final DocumentRepository documentRepository;
		private final DocumentMapper documentMapper;
		private final ThreadLimiter threadLimiter;

		@Transactional
		@Retryable(maxAttempts = 10, backoff = @Backoff(delay = 5), retryFor = ThreadLimitExceededException.class)
		public DocumentDto createDocument(DocumentDto documentDto) {
			if (threadLimiter.checkAvailableThread()) {
				Document newDocument = documentMapper.toEntity(documentDto);
				newDocument = documentRepository.save(newDocument);
				return documentMapper.toDto(newDocument);
			}
			throw new ThreadLimitExceededException(
					String.format("Exceeded the thread limit in %s", threadLimiter.getTimeUnit()));
		}
	}

	@Mapper(componentModel = "spring")
	public interface DocumentMapper {
		Document toEntity(DocumentDto documentDto);
		DocumentDto toDto(Document document);
	}

	@Repository
	public interface DocumentRepository extends JpaRepository<Document, Long> {
	}

	@Data
	@Entity
	@AllArgsConstructor
	@NoArgsConstructor
	@Table(name = "document")
	static class Document {
		@Id
		@GeneratedValue(strategy = GenerationType.IDENTITY)
		private Long doc_id;

		@OneToOne(mappedBy = "document")
		private Description description;

		@Column(name = "status")
		private String status;

		@Column(name = "doc_type")
		@Enumerated(EnumType.STRING)
		private DocumentType doc_type;

		@Column(name = "import_request")
		private boolean importRequest;

		@Column(name = "owner_inn")
		private String owner_inn;

		@Column(name = "participant_inn")
		private String participant_inn;

		@Column(name = "producer_inn")
		private String producer_inn;

		@CreationTimestamp
		@Temporal(TemporalType.TIMESTAMP)
		@Column(name = "production_date", nullable = false)
		private LocalDateTime production_date;

		@Column(name = "production_type")
		private String production_type;

		@OneToMany(mappedBy = "document")
		private List<Product> products;

		@CreationTimestamp
		@Temporal(TemporalType.TIMESTAMP)
		@Column(name = "reg_date", nullable = false)
		private LocalDateTime reg_date;

		@Column(name = "reg_number")
		private String reg_number;
	}

	@Data
	@Entity
	@AllArgsConstructor
	@NoArgsConstructor
	@Table(name = "description")
	static class Description {
		@Id
		@GeneratedValue(strategy = GenerationType.IDENTITY)
		private Long id;

		@Column(name = "participant_inn", nullable = false)
		private String participantInn;

		@OneToOne
		@JoinColumn(name = "doc_id")
		private Document document;
	}

	@Data
	@Entity
	@AllArgsConstructor
	@NoArgsConstructor
	@Table(name = "product")
	static class Product {
		@Id
		@GeneratedValue(strategy = GenerationType.IDENTITY)
		private Long id;

		@Column(name = "certificate_document")
		private String certificate_document;

		@CreationTimestamp
		@Temporal(TemporalType.TIMESTAMP)
		@Column(name = "certificate_document_date", nullable = false)
		private LocalDateTime certificate_document_date;

		@Column(name = "owner_inn")
		private String owner_inn;

		@CreationTimestamp
		@Temporal(TemporalType.TIMESTAMP)
		@Column(name = "production_date", nullable = false)
		private LocalDateTime production_date;

		@Column(name = "tnved_code")
		private String tnved_code;

		@Column(name = "uit_code")
		private String uit_code;

		@Column(name = "uitu_code")
		private String uitu_code;

		@ManyToOne
		@JoinColumn(name = "doc_id", nullable = false)
		private Document document;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class DocumentDto {
		private Long[] description;
		@Null
		private Long doc_id;
		private String status;
		private DocumentType doc_type;
		private boolean importRequest;
		private String owner_inn;
		private String participant_inn;
		private String producer_inn;
		private LocalDateTime production_date;
		private String production_type;
		private Long[] products;
		private LocalDateTime reg_date;
		private String reg_number;
	}

	enum DocumentType {
		LP_INTRODUCE_GOODS
	}

	static class ThreadLimitExceededException extends RuntimeException {
		public ThreadLimitExceededException(String message) {
			super(message);
		}
	}
}
