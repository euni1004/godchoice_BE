package com.team03.godchoice.service.socialLogin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team03.godchoice.SSE.NotificationRepository;
import com.team03.godchoice.domain.Member;
import com.team03.godchoice.domain.RefreshToken;
import com.team03.godchoice.domain.SocialAccessToken;
import com.team03.godchoice.enumclass.Role;
import com.team03.godchoice.dto.GlobalResDto;
import com.team03.godchoice.dto.responseDto.UserInfoDto;
import com.team03.godchoice.dto.social.SocialUserInfoDto;
import com.team03.godchoice.dto.TokenDto;
import com.team03.godchoice.exception.CustomException;
import com.team03.godchoice.exception.ErrorCode;
import com.team03.godchoice.interfacepackage.LoginInterface;
import com.team03.godchoice.repository.MemberRepository;
import com.team03.godchoice.repository.RefreshTokenRepository;
import com.team03.godchoice.repository.SocialAccessTokenRepository;
import com.team03.godchoice.security.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class SocialKakaoService implements LoginInterface {

    @Value("${spring.security.oauth2.client.registration.kakao.client-id}")
    private String kakaoClientId;
    @Value("${spring.security.oauth2.client.registration.kakao.client-secret}")
    private String kakaoClientSecret;
    @Value("${spring.security.oauth2.client.registration.kakao.redirect-uri}")
    private String redirect_uri;
    @Value("${spring.security.oauth2.client.provider.kakao.user-info-uri}")
    private String user_info_uri;
    @Value("${spring.security.oauth2.client.provider.kakao.token-uri}")
    private String token_uri;

    public final MemberRepository memberRepository;
    public final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationRepository notificationRepository;
    private final SocialAccessTokenRepository socialAccessTokenRepository;
    public final JwtUtil jwtUtil;

    public GlobalResDto<?> kakaoLogin(String code, HttpServletResponse response) throws JsonProcessingException {

        //인가코드를 통해 access_token 발급받기
        String accessToken = issuedAccessToken(code);

        //access_token을 통해 사용자 정보가져오기
        SocialUserInfoDto socialUserInfoDto = getKakaoUserInfo(accessToken);

        //사용자정보를 토대로 가입진행하기(일단 DB에 저장이 되어있는지 확인후)
        Member member = saveMember(socialUserInfoDto,accessToken);

        //강제 로그인 처리
        forceLoginUser(member);

        //리프레쉬, 액세스 토큰 만들기
        //토큰 발급후 response
        createToken(member,response);

        //아직 읽지않은 알림 갯수 표시
        Long notificationNum = notificationRepository.countUnReadStateNotifications(member.getMemberId());

        UserInfoDto userInfoDto = new UserInfoDto(member,notificationNum);

        return GlobalResDto.success(userInfoDto, "로그인이 완료되었습니다");
    }

    //인가코드를 통해 access_token 발급받기
    public String issuedAccessToken(String code) throws JsonProcessingException {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // HTTP Body 생성
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", kakaoClientId);
        body.add("redirect_uri", redirect_uri);
        body.add("code", code);
        body.add("client_secret", kakaoClientSecret);

        // HTTP 요청 보내기
        HttpEntity<MultiValueMap<String, String>> kakaoTokenRequest =
                new HttpEntity<>(body, headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                token_uri,
                HttpMethod.POST,
                kakaoTokenRequest,
                String.class
        );

        // HTTP 응답 (JSON) -> 액세스 토큰 파싱
        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        String accessToken = jsonNode.get("access_token").asText();

        return accessToken;
    }

    //access_token을 통해 사용자 정보가져오기
    private SocialUserInfoDto getKakaoUserInfo(String accessToken) throws JsonProcessingException {
        // HTTP Header 생성
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // HTTP 요청 보내기
        HttpEntity<MultiValueMap<String, String>> kakaoUserInfoRequest = new HttpEntity<>(headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                user_info_uri,
                HttpMethod.POST,
                kakaoUserInfoRequest,
                String.class
        );

        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        Long id = jsonNode.get("id").asLong();
        String nickname = jsonNode.get("properties")
                .get("nickname").asText();
        String email = jsonNode.get("kakao_account")
                .get("email").asText();
        String userImgUrl = jsonNode.get("properties")
                .get("profile_image").asText();

        return new SocialUserInfoDto(id, nickname, email, userImgUrl);
    }

    //사용자정보를 토대로 가입진행하기(일단 DB에 저장이 되어있는지 확인후)
    public Member saveMember(SocialUserInfoDto socialUserInfoDto,String accessToken) {
        Member kakaoMember = memberRepository.findByEmail("k_"+socialUserInfoDto.getEmail()).orElse(null);

        //없다면 저장
        if (kakaoMember == null) {
            Role role;
            if (memberRepository.findAll().isEmpty()) {
                role = Role.ADMIN;
            } else {
                role = Role.USER;
            }

            Member member = Member.builder().
                    email("k_" + socialUserInfoDto.getEmail())
                    .userName(socialUserInfoDto.getNickname())
                    .userImgUrl(socialUserInfoDto.getUserImgUrl())
                    .pw(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .isAccepted(false)
                    .isDeleted(false)
                    .role(role)
                    .build();

            memberRepository.save(member);
            return member;
        }

        SocialAccessToken socialAccessToken = new SocialAccessToken(accessToken,"k_" + socialUserInfoDto.getEmail(),"kakao");
        socialAccessTokenRepository.save(socialAccessToken);

        //있다면 member 반환
        return kakaoMember;
    }

    public void createToken(Member member,HttpServletResponse response){
        TokenDto tokenDto = jwtUtil.createAllToken(member.getEmail());

        Optional<RefreshToken> refreshToken = refreshTokenRepository.findByAccountEmail(member.getEmail());

        if (refreshToken.isPresent()) {
            refreshTokenRepository.save(refreshToken.get().updateToken(tokenDto.getRefreshToken()));
        } else {
            RefreshToken newToken = new RefreshToken(tokenDto.getRefreshToken(), member.getEmail());
            refreshTokenRepository.save(newToken);
        }

        setHeader(response, tokenDto);
    }

    public GlobalResDto<?> logoutKakao(Member member){

        //소셜 토큰 가져오기
        SocialAccessToken accessToken = socialAccessTokenRepository.findByAccountEmail(member.getEmail()).orElseThrow(()-> new CustomException(ErrorCode.ERROR));

        //토큰으로 카카오에 로그아웃 요청
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken.getAccessToken());
        headers.add("Content-type", "application/x-www-form-urlencoded");

        // HTTP 요청 보내기
        HttpEntity<MultiValueMap<String, String>> kakaoLogoutRequest = new HttpEntity<>(headers);
        RestTemplate rt = new RestTemplate();
        try {
            ResponseEntity<String> response = rt.exchange(
                    "https://kapi.kakao.com/v1/user/logout",
                    HttpMethod.POST,
                    kakaoLogoutRequest,
                    String.class
            );
        }catch (Exception e){
            throw new CustomException(ErrorCode.ERROR);
        }

        socialAccessTokenRepository.delete(accessToken);

        return GlobalResDto.success(null, "로그아웃 완료");
    }

}
