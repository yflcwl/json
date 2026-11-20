package demo.hb.web.dto;

import java.util.List;

public record GenerateInput(String message, String skillId, List<String> images) {
}
