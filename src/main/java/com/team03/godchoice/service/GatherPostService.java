package com.team03.godchoice.service;

import com.team03.godchoice.domain.Member;
import com.team03.godchoice.domain.domainenum.RegionTag;
import com.team03.godchoice.domain.gatherPost.GatherPost;
import com.team03.godchoice.domain.gatherPost.GatherPostComment;
import com.team03.godchoice.domain.gatherPost.GatherPostImg;
import com.team03.godchoice.dto.GlobalResDto;
import com.team03.godchoice.dto.requestDto.gatherpostDto.GatherPostRequestDto;
import com.team03.godchoice.dto.requestDto.gatherpostDto.GatherPostUpdateDto;
import com.team03.godchoice.dto.responseDto.gatherpost.GatherPostResponseDto;
import com.team03.godchoice.exception.CustomException;
import com.team03.godchoice.exception.ErrorCode;
import com.team03.godchoice.repository.MemberRepository;
import com.team03.godchoice.repository.gatherpost.GatherPostImgRepository;
import com.team03.godchoice.repository.gatherpost.GatherPostRepository;
import com.team03.godchoice.s3.S3Uploader;
import com.team03.godchoice.security.jwt.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatherPostService {
    private final MemberRepository memberRepository;
    private final GatherPostRepository gatherPostRepository;
    private final GatherPostImgRepository gatherPostImgRepository;
    private final EventPostService eventPostService;
    private final S3Uploader s3Uploader;

    @Transactional
    public GlobalResDto<?> createGather(GatherPostRequestDto gatherPostDto, List<MultipartFile> multipartFile, UserDetailsImpl userDetails) throws IOException {

        Member member = memberCheck(userDetails);

        // 만남시간, lacalDate로 바꾸고 주소 태그만들고
        log.info("gatherPostDto");
        LocalDate date = LocalDate.parse(gatherPostDto.getDate(), DateTimeFormatter.ISO_DATE);
        RegionTag regionTag = eventPostService.toRegionTag(gatherPostDto.getPostAddress());
        String gatherStatus = eventPostService.toEventStatus(date);

        // dto내용과 사용자 저장
        GatherPost gatherPost = new GatherPost(gatherPostDto, date, regionTag, gatherStatus, member);

        gatherPostRepository.save(gatherPost);

        // List로 image받은후 저장
        saveImg(multipartFile, gatherPost);

        return GlobalResDto.success(null, "success create gatherPost");
    }

    @Transactional
    public GlobalResDto<?> updateGatherPost(Long postId, GatherPostUpdateDto gatherPostDto, List<MultipartFile> multipartFile, UserDetailsImpl userDetails) throws IOException {

        Member member = memberCheck(userDetails);

        GatherPost gatherPost = postCheck(postId);

        LocalDate date = LocalDate.parse(gatherPostDto.getDate(), DateTimeFormatter.ISO_DATE);
        RegionTag regionTag = eventPostService.toRegionTag(gatherPostDto.getPostAddress());
        String gatherStatus = eventPostService.toEventStatus(date);
        gatherPost.update(gatherPostDto, date, regionTag, gatherStatus, member);

        if (gatherPost.getMember().getEmail().equals(member.getEmail())) {
            String[] imgIdlist = gatherPostDto.getImgId().split(",");
            //저장되어있는 사진 리스트 크기와 받아온 숫자 리스트 크기가 같다면 올린 사진을 모두 삭제하는것이므로 기본이미지 넣기
            if (imgIdlist.length == gatherPost.getGatherPostImg().size()) {
                List<GatherPostImg> gatherPostImgs = gatherPostImgRepository.findAllByGatherPost(gatherPost);
                for (GatherPostImg gatherPostImg : gatherPostImgs) {
                    String imgUrl = gatherPostImg.getImgUrl().substring(50);
                    s3Uploader.delImg(imgUrl);
                }
                gatherPostImgRepository.deleteAllByGatherPost(gatherPost);
                String gatherPostUrl = "https://kimbiibucket.s3.ap-northeast-2.amazonaws.com/normal_profile.jpg";
                GatherPostImg gatherPostImg = new GatherPostImg(gatherPostUrl, gatherPost);
                gatherPostImgRepository.save(gatherPostImg);
            } else if (imgIdlist.length != 0) {
                for (String imgId : imgIdlist) {
                    Long gatherPostId = Long.valueOf(imgId);
                    GatherPostImg gatherPostImg = gatherPostImgRepository.findByGatherPostImgId(gatherPostId);
                    String path = gatherPostImg.getImgUrl().substring(50);
                    s3Uploader.delImg(path);
                    gatherPostImgRepository.deleteById(gatherPostId);
                }
            }
            saveImg(multipartFile, gatherPost);

            return GlobalResDto.success(null, "success update gatherpost");
        } else {
            throw new CustomException(ErrorCode.NO_PERMISSION_CHANGE);
        }
    }

    @Transactional
    public GlobalResDto<?> deleteGatherPost(Long postId, UserDetailsImpl userDetails) {

        Member member = memberCheck(userDetails);

        GatherPost gatherPost = postCheck(postId);

        if (gatherPost.getMember().getEmail().equals(member.getEmail())) {
            List<GatherPostImg> gatherPostImgs = gatherPostImgRepository.findAllByGatherPost(gatherPost);
            for (GatherPostImg gatherPostImg : gatherPostImgs) {
                String imgUrl = gatherPostImg.getImgUrl().substring(50);
                s3Uploader.delImg(imgUrl);
            }
            gatherPostRepository.deleteById(postId);

            return GlobalResDto.success(null, "success delete gatherpost");
        } else {
            throw new CustomException(ErrorCode.NO_PERMISSION_DELETE);
        }
    }

    public GlobalResDto<?> getGatherPost(Long postId, UserDetailsImpl userDetails, HttpServletRequest req, HttpServletResponse res) {
        viewCountUp(postId, req, res);

        memberCheck(userDetails);

        GatherPost gatherPost = postCheck(postId);

        List<GatherPostImg> gatherPostImgs = gatherPostImgRepository.findAllByGatherPost(gatherPost);
        List<String> imgUrl = new ArrayList<>();
        for (GatherPostImg gatherPostImg : gatherPostImgs) {
            imgUrl.add(gatherPostImg.getImgUrl());
        }

        List<com.team03.godchoice.dto.responseDto.CommentDto> commentDtoList = new ArrayList<>();
        for(GatherPostComment comment : gatherPost.getComments()){
            if(comment.getParent() == null){
                commentDtoList.add(new com.team03.godchoice.dto.responseDto.CommentDto(comment));
            }
        }

        return GlobalResDto.success(new GatherPostResponseDto(gatherPost, imgUrl, commentDtoList),null);

    }

    public Member memberCheck(UserDetailsImpl userDetails) {
        return memberRepository.findById(userDetails.getMember().getMemberId()).orElseThrow(
                () -> new CustomException(ErrorCode.NOT_FOUND_MEMBER)
        );
    }

    public GatherPost postCheck(Long postId) {
        return gatherPostRepository.findById(postId).orElseThrow(
                () -> new CustomException(ErrorCode.NOT_FOUND_POST)
        );
    }

    public void saveImg(List<MultipartFile> multipartFile, GatherPost gatherPost) throws IOException {
        String gatherPostUrl;
        if (multipartFile.size() != 0) {
            for (MultipartFile file : multipartFile) {
                gatherPostUrl = s3Uploader.uploadFiles(file, "testdir");
                GatherPostImg gatherPostImg = new GatherPostImg(gatherPostUrl, gatherPost);
                gatherPostImgRepository.save(gatherPostImg);
            }
        } else {
            gatherPostUrl = "https://kimbiibucket.s3.ap-northeast-2.amazonaws.com/normal_profile.jpg";
            GatherPostImg gatherPostImg = new GatherPostImg(gatherPostUrl, gatherPost);
            gatherPostImgRepository.save(gatherPostImg);
        }
    }

    public void viewCountUp(Long id, HttpServletRequest req, HttpServletResponse res) {

        Cookie oldCookie = null;

        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("postView")) {
                    oldCookie = cookie;
                }
            }
        }

        if (oldCookie != null) {
            if (!oldCookie.getValue().contains("[" + id.toString() + "]")) {
                viewCountUp(id);
                oldCookie.setValue(oldCookie.getValue() + "_[" + id + "]");
                oldCookie.setPath("/");
                oldCookie.setMaxAge(60 * 60 * 24);
                res.addCookie(oldCookie);
            }
        } else {
            viewCountUp(id);
            Cookie newCookie = new Cookie("postView","[" + id + "]");
            newCookie.setPath("/");
            newCookie.setMaxAge(60 * 60 * 24);
            res.addCookie(newCookie);
        }
    }

    @Transactional
    public void viewCountUp(Long gatherPostId) {
        GatherPost gatherPost = gatherPostRepository.findByGatherPostId(gatherPostId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_POST));
        gatherPost.viewCountUp();
        gatherPostRepository.save(gatherPost);
    }

}
