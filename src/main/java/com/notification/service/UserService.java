package  com.notification.service;
import com.notification.dto.UserRegistrationRequest;
import com.notification.dto.UserResponse;
import com.notification.exception.UserNotFoundException;
import com.notification.model.User;
import com.notification.model.UserChannelPreference;
import com.notification.model.enums.NotificationChannel;
import com.notification.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    @Transactional
    @CachePut(value = "users", key = "#result.id")
    public UserResponse registerUser(UserRegistrationRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists: " + request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .deviceToken(request.getDeviceToken())
                .build();
        User savedUser = userRepository.save(user);
        if (request.getPreferredChannels() != null && !request.getPreferredChannels().isEmpty()) {
            List<UserChannelPreference> preferences = request.getPreferredChannels().stream()
                    .map(channel -> UserChannelPreference.builder()
                            .user(savedUser)
                            .channel(channel)
                            .enabled(true)
                            .build())
                    .collect(Collectors.toList());
            savedUser.getChannelPreferences().addAll(preferences);
            userRepository.save(savedUser);
        }
        log.info("User registered successfully: {}", savedUser.getUsername());
        return UserResponse.fromEntity(savedUser);
    }
    @Transactional(readOnly = true)
    @Cacheable(value = "users", key = "#id")
    public UserResponse getUserById(Long id) {
        User user = findUserById(id);
        return UserResponse.fromEntity(user);
    }
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(UserResponse::fromEntity)
                .collect(Collectors.toList());
    }
    @Transactional
    @CacheEvict(value = "users", key = "#userId")
    public UserResponse updateChannelPreferences(Long userId, List<NotificationChannel> channels) {
        User user = findUserById(userId);
        user.getChannelPreferences().clear();
        List<UserChannelPreference> newPreferences = channels.stream()
                .map(channel -> UserChannelPreference.builder()
                        .user(user)
                        .channel(channel)
                        .enabled(true)
                        .build())
                .collect(Collectors.toList());
        user.getChannelPreferences().addAll(newPreferences);
        User savedUser = userRepository.save(user);
        log.info("Updated channel preferences for user: {}", userId);
        return UserResponse.fromEntity(savedUser);
    }
    public User findUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
    }
}