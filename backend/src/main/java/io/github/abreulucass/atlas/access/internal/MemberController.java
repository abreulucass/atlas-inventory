package io.github.abreulucass.atlas.access.internal;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
class MemberController {

	private final Members members;

	MemberController(Members members) {
		this.members = members;
	}

	@GetMapping
	List<MemberView> list() {
		return members.list();
	}

}
