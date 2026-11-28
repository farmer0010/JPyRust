package example;

import com.jpyrust.spring.JPyRustTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
}
