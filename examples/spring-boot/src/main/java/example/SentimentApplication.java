package example;

import com.jpyrust.spring.JPyRustTemplate;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@SpringBootApplication
@RestController
public class SentimentApplication {

    private final JPyRustTemplate python;

    public SentimentApplication(JPyRustTemplate python) {
        this.python = python;
    }

    public static void main(String[] args) {
        SpringApplication.run(SentimentApplication.class, args);
    }

    @PostMapping(value = "/sentiment", produces = MediaType.APPLICATION_JSON_VALUE)
    public String sentiment(@RequestBody String text) {
        return python.call("SENTIMENT", text);
    }

    @PostMapping(value = "/sentiment/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody sentimentStream(@RequestBody String text) {
        return out -> {
            try (Stream<String> sentences = python.callStream("SENTENCES", text)) {
                for (String json : (Iterable<String>) sentences::iterator) {
                    out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        };
    }
}
