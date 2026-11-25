package io.github.abreulucass.atlas.access.internal;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Request fields are plain strings with no {@code @Valid}: {@link Members} judges them in the spec's order. */
@RestController
@RequestMapping("/api/members")
class MemberController {

	private final Members members;

	MemberController(Members members) {
		this.members = members;
	}

	record AddMember(String email, String role) {
	}

	@GetMapping
	List<MemberView> list() {
		return members.list();
	}

	@PostMapping
	ResponseEntity<MemberView> add(@RequestBody AddMember request) {
		MemberView member = members.add(request.email(), request.role());
		return ResponseEntity.created(URI.create("/api/members/" + member.userId())).body(member);
	}

}
