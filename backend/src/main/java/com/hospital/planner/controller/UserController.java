package com.hospital.planner.controller;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.MeDto;
import com.hospital.planner.dto.UserDto;
import com.hospital.planner.entity.User;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.UserRepository;
import com.hospital.planner.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {
    public record CreateUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,40}", message = "3-40 letters, digits, . _ -") String username,
                             @NotBlank @Size(min = 8, max = 100, message = "at least 8 characters") String password,
                             @NotBlank @Size(max = 120) String fullName, @Size(max = 200) String email,
                             @NotBlank String role, Long departmentId) {}

    /** Every field is optional: omitted fields keep their stored values. */
    public record UpdateUser(@Size(max = 120) String fullName, @Size(max = 200) String email, String role,
                             Long departmentId, Boolean active) {}

    public record ResetPassword(@NotBlank @Size(min = 8, max = 100, message = "at least 8 characters") String password) {}

    private final UserService users;
    private final UserRepository repo;

    public UserController(UserService users, UserRepository repo) {
        this.users = users;
        this.repo = repo;
    }

    /** Signed-in user, role and department (for the UI). */
    @GetMapping("/me")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public MeDto me() {
        User u = repo.findByUsernameIgnoreCase(CurrentUser.username()).orElseThrow(() -> ApiException.notFound("User"));
        return new MeDto(u.username, u.fullName, u.role, List.of(u.role), u.department == null ? "" : u.department.id,
                u.department == null ? "" : u.department.name);
    }

    @GetMapping("/users")
    public PageResult<UserDto> list(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
                                    @RequestParam(required = false) Boolean active,
                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return users.list(q, role, active, page, size);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody CreateUser r) {
        return users.create(r.username(), r.password(), r.fullName(), r.email(), r.role(), r.departmentId());
    }

    @PatchMapping("/users/{id}")
    public UserDto update(@PathVariable long id, @Valid @RequestBody UpdateUser r) {
        return users.update(id, r.fullName(), r.email(), r.role(), r.departmentId(), r.active());
    }

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable long id, @Valid @RequestBody ResetPassword r) {
        users.resetPassword(id, r.password());
    }
}
