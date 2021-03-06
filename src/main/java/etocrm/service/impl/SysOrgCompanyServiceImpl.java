package etocrm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import jxl.Workbook;
import jxl.format.Alignment;
import jxl.format.Border;
import jxl.format.BorderLineStyle;
import jxl.format.UnderlineStyle;
import jxl.write.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.etocrm.convert.SysOrgCompanyNoticeConvert;
import org.etocrm.database.enums.ResponseEnum;
import org.etocrm.database.exception.MyException;
import org.etocrm.database.util.BasePage;
import org.etocrm.database.util.PageBounds;
import org.etocrm.database.util.QueryConditionUtil;
import org.etocrm.database.util.SysUserRedisVO;
import org.etocrm.entity.*;
import org.etocrm.enums.BusinessEnum;
import org.etocrm.enums.OtherSystemUrlTypeEnum;
import org.etocrm.exception.UamException;
import org.etocrm.model.company.*;
import org.etocrm.repository.*;
import org.etocrm.service.SysOrgBrandsService;
import org.etocrm.service.SysOrgCompanyBrandsService;
import org.etocrm.service.SysOrgCompanyService;
import org.etocrm.utils.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.persistence.criteria.Predicate;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * <p>
 * ?????????????????? ???????????????
 * </p>
 *
 * @author admin
 * @since 2021-01-23
 */
@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class SysOrgCompanyServiceImpl implements SysOrgCompanyService {
    @Value("${companyCallBackUrl}")
    private String callBackUrl;

    @Resource
    private SysOrgCompanyRepository sysOrgCompanyRepository;

    @Resource
    private SysOrgCompanyBrandsService sysOrgCompanyBrandsService;

    @Autowired
    private SysOrgCompanyBrandsRepository sysOrgCompanyBrandsRepository;

    @Resource
    private SysOrgBrandsService sysOrgBrandsService;

    @Resource
    private SysOrgBrandsRepository sysOrgBrandsRepository;

    @Autowired
    private SecurityUtil securityUtil;

    private CopyOnWriteArrayList<ImportCompanyFailVO> list = Lists.newCopyOnWriteArrayList();

    @Autowired
    private UpLoadUtil upLoadUtil;

    @Autowired
    private ImportHistoryRepository importHistoryRepository;

    @Autowired
    private ImportCompanyFailRepository importCompanyFailRepository;

    @Autowired
    private ImportTemplateRepository importTemplateRepository;

    @Autowired
    private OtherSystemNoticeUtil otherSystemNoticeUtil;

    @Autowired
    private BrandsWechatInfoRepository brandsWechatInfoRepository;

    @Autowired
    private SysOrgRepository sysOrgRepository;

    @Autowired
    private MachineRoomRepository machineRoomRepository;

    @Resource
    private SysIndustryRepository sysIndustryRepository;

    @Resource
    SysOrgAuthRepository sysOrgAuthRepository;


    @Autowired
    private SysOrgCompanyNoticeConvert convert;


    @Override
    public Long saveSysOrgCompany(SysOrgCompanySaveVO sysOrgCompany) throws MyException {
        SysUserRedisVO loginUser = securityUtil.getCurrentLoginUser();
        Integer count = sysOrgCompanyRepository.countByOrgIdAndName(loginUser.getUamOrgId(), sysOrgCompany.getName());
        if (count > 0) {
            throw new MyException(ResponseEnum.FAILD.getCode(), "????????????????????????????????????");
        }
        SysOrgCompany sysOrgCompany1 = new SysOrgCompany();
        BeanUtil.copyProperties(sysOrgCompany, sysOrgCompany1);
        if (BusinessEnum.COMPANY_AUTO.getCode().equals(sysOrgCompany.getCodeType())) {
            sysOrgCompany1.setCode(System.currentTimeMillis() + "");
        }
        sysOrgCompany1.setOrgId(loginUser.getUamOrgId());
        sysOrgCompanyRepository.save(sysOrgCompany1);
        //????????????ids??????
        SysOrgCompanyBrands vo = new SysOrgCompanyBrands();
        vo.setBrandsId(sysOrgCompany.getBrandId());
        vo.setCompanyId(sysOrgCompany1.getId());
        vo.setOrgId(loginUser.getUamOrgId());
        vo.setMiniAppId(sysOrgCompany.getMiniAppId());
        vo.setServiceId(sysOrgCompany.getServiceAppId());
        sysOrgCompanyBrandsRepository.save(vo);
        //??????
        companyAddOrUpdateNotice(sysOrgCompany.getBrandId(),
                sysOrgCompany.getMiniAppId(),
                sysOrgCompany.getServiceAppId(),
                sysOrgCompany1, OtherSystemUrlTypeEnum.SUBSIDIARY_ADD_REDIRECT);
        return sysOrgCompany1.getId();
    }

    /**
     * ??????????????????
     *
     * @param brandId                ??????id
     * @param miniAppId              ?????????id???
     * @param serviceAppId           ?????????id
     * @param sysOrgCompany          ???????????????
     * @param otherSystemUrlTypeEnum ??????????????????
     * @throws MyException
     */
    private void companyAddOrUpdateNotice(Long brandId, Long miniAppId, Long serviceAppId, SysOrgCompany sysOrgCompany, OtherSystemUrlTypeEnum otherSystemUrlTypeEnum) throws MyException {
        SysOrgCompanyNoticeVO sysOrgCompanyNoticeVO = convert.doToVo(sysOrgCompany);
        Optional<SysOrgBrands> byId = sysOrgBrandsRepository.findById(brandId);
        if (byId.isPresent()) {
            SysOrgBrands sysOrgBrands = byId.get();
            //???crmBrandId ????????? uamBrandId
            sysOrgCompanyNoticeVO.setCrmBrandId(sysOrgBrands.getId());
            sysOrgCompanyNoticeVO.setUamBrandId(sysOrgBrands.getId());
            sysOrgCompanyNoticeVO.setBrandName(sysOrgBrands.getName());
        }
        //??????
        //crmOrgId uamBrandId crmBrandId appId  miniAppName serviceAppName
        //?????? ?????????????????????id ????????????
        if (null != miniAppId) {
            Optional<BrandsWechatInfo> miniAppOptional = brandsWechatInfoRepository.findById(miniAppId);
            if (miniAppOptional.isPresent()) {
                sysOrgCompanyNoticeVO.setMiniAppId(miniAppOptional.get().getAppid());
                sysOrgCompanyNoticeVO.setMiniAppName(miniAppOptional.get().getWechatName());
            }
        }
        if (null != serviceAppId) {
            Optional<BrandsWechatInfo> serviceAppOptional = brandsWechatInfoRepository.findById(serviceAppId);
            if (serviceAppOptional.isPresent()) {
                sysOrgCompanyNoticeVO.setServiceAppId(serviceAppOptional.get().getAppid());
                sysOrgCompanyNoticeVO.setServiceAppName(serviceAppOptional.get().getWechatName());
            }
        }
        Optional<SysOrg> sysOrgOptional = sysOrgRepository.findById(sysOrgCompanyNoticeVO.getUamOrgId());
        Long machineId = null;
        if (sysOrgOptional.isPresent()) {
            machineId = sysOrgOptional.get().getMachineId();
            Optional<MachineRoom> byId1 = machineRoomRepository.findById(machineId);
            if (byId1.isPresent()) {
                MachineRoom machineRoom = byId1.get();
                sysOrgCompanyNoticeVO.setMachineId(machineRoom.getId());
                sysOrgCompanyNoticeVO.setMachineCode(machineRoom.getCode());
                sysOrgCompanyNoticeVO.setMachineName(machineRoom.getRoomName());
            }
            //??????
            SysOrgIndustry byOrgId = sysOrgAuthRepository.findByOrgId(sysOrgOptional.get().getId());
            sysOrgCompanyNoticeVO.setIndustryId(byOrgId.getId());
            Optional<SysIndustry> byId2 = sysIndustryRepository.findById(byOrgId.getId());
            if (byId2.isPresent()) {
                sysOrgCompanyNoticeVO.setIndustryName(byId2.get().getName());
            }
        }
        //??????????????????
        sysOrgCompanyNoticeVO.setCallbackUrl(callBackUrl);
        List<SysOrgCompanyNoticeVO> list = new ArrayList<>();
        list.add(sysOrgCompanyNoticeVO);
        String dataJsonStr = JSON.toJSONString(list, SerializerFeature.WriteMapNullValue);
        otherSystemNoticeUtil.restTemplate(sysOrgCompanyNoticeVO.getUamOrgId(), machineId, dataJsonStr, otherSystemUrlTypeEnum);
    }

    @Override
    public Long updateByPk(SysOrgCompanyUpdateVO sysOrgCompany) throws MyException {
        Integer count = sysOrgCompanyRepository.countByNameAndIdNot(sysOrgCompany.getName(), sysOrgCompany.getId());
        if (count > 0) {
            throw new MyException(ResponseEnum.FAILD.getCode(), "???????????????,??????????????????");
        }
        SysOrgCompany sysOrgCompany1 = new SysOrgCompany();
        BeanUtil.copyProperties(sysOrgCompany, sysOrgCompany1);
        SysOrgCompany update = sysOrgCompanyRepository.update(sysOrgCompany1);
        //????????????ids??????
        SysUserRedisVO loginUser = securityUtil.getCurrentLoginUser();
        //????????????
        List<SysOrgCompanyBrands> byOrgId = sysOrgCompanyBrandsRepository.findByCompanyId(sysOrgCompany.getId());
        sysOrgCompanyBrandsRepository.deleteAll(byOrgId);
        //????????????
        SysOrgCompanyBrands sysOrgCompanyBrands = new SysOrgCompanyBrands();
        sysOrgCompanyBrands.setBrandsId(sysOrgCompany.getBrandId());
        sysOrgCompanyBrands.setOrgId(loginUser.getUamOrgId());
        sysOrgCompanyBrands.setCompanyId(sysOrgCompany.getId());
        sysOrgCompanyBrands.setMiniAppId(sysOrgCompany.getMiniAppId());
        sysOrgCompanyBrands.setServiceId(sysOrgCompany.getServiceId());
        sysOrgCompanyBrandsRepository.save(sysOrgCompanyBrands);

        //????????????
        companyAddOrUpdateNotice(sysOrgCompany.getBrandId(),
                sysOrgCompany.getMiniAppId(),
                sysOrgCompany.getServiceId(), update,
                OtherSystemUrlTypeEnum.SUBSIDIARY_UPDATE_NOTICE_REDIRECT);
        return sysOrgCompany1.getId();
    }

    @Override
    public Long updateStatus(UpdateCompanyStatusVO pk) throws MyException {
        SysOrgCompany sysOrgCompany1 = new SysOrgCompany();
        BeanUtil.copyProperties(pk, sysOrgCompany1);
        sysOrgCompanyRepository.update(sysOrgCompany1);
        //todo ????????????ids??????
        return sysOrgCompany1.getId();
    }

    @Override
    public BasePage<SysOrgCompanyVO> getCompanyList(Integer curPage, Integer size, SysCompanyPageListVO v) {
        SysUserRedisVO loginUser = securityUtil.getCurrentLoginUser();


        Page<SysOrgCompany> sysParamIPage = sysOrgCompanyRepository.findAll((Specification<SysOrgCompany>) (root, query, cb) -> {
                    //?????? ????????????????????????
                    List<Predicate> list = new ArrayList<>();

                    if (null != v.getStatus()) {
                        Predicate status = cb.equal(root.get("status").as(Integer.class), v.getStatus());
                        list.add(status);
                    }
                    if (null != v.getOpenShop()) {
                        Predicate openShop = cb.equal(root.get("openShop").as(Integer.class), v.getOpenShop());
                        list.add(openShop);
                    }
                    Predicate orgId = cb.equal(root.get("orgId").as(Integer.class), loginUser.getUamOrgId());
                    list.add(orgId);
                    return cb.and(list.toArray(new Predicate[0]));
                }
                , PageRequest.of(curPage, size,
                        Sort.by(Sort.Direction.DESC, "updatedTime")));
        BasePage basePage = new BasePage<>(sysParamIPage);
        List<SysOrgCompany> records = (List<SysOrgCompany>) basePage.getRecords();
        List<SysOrgCompanyVO> transformation = this.trandsFormListVO(records);
        basePage.setRecords(transformation);
        return basePage;
    }

    private List<SysOrgCompanyVO> trandsFormListVO(List<SysOrgCompany> records) {
        List<SysOrgCompanyVO> res = Lists.newArrayList();
        if (CollectionUtil.isNotEmpty(records)) {
            records.forEach(
                    item -> {
                        SysOrgCompanyVO sysOrgCompanyVO = new SysOrgCompanyVO();
                        BeanUtil.copyProperties(item, sysOrgCompanyVO);
                        res.add(sysOrgCompanyVO);
                    });
        }
        return res;
    }

    @Override
    public Long importCompany(MultipartFile file) {

        List<ImportCompanyVO> list;
        Map<String, String> map = Maps.newLinkedHashMap();
        map.put("???????????? 2-????????? 3-?????????*", "type");
        map.put("????????????*", "name");
        map.put("??????ID", "companyId");
        map.put("?????????", "contacts");
        map.put("????????????", "contactsPhone");
        map.put("????????????ID", "parentId");
        map.put("????????????", "code");
        map.put("????????????????????????\n" +
                "1-??????  2-?????????", "openShop");
        map.put("????????????ID \n" +
                "??????????????????????????????", "brandsId");
        try {
            list = ExcelUtil.excelToList(file, ImportCompanyVO.class, map);
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new UamException(ResponseEnum.FAILD.getCode(), "?????????????????????");
        }
        if (list.size() == 0) {
            throw new UamException(ResponseEnum.FAILD.getCode(), "????????????????????????");
        }
        if (list.size() > 2000) {
            throw new UamException(ResponseEnum.FAILD.getCode(), "????????????,??????Excel??????2000??????,???????????????");
        }
        CopyOnWriteArrayList<ImportCompanyVO> cowList = new CopyOnWriteArrayList(list.toArray());
        String importBatch = UUID.randomUUID().toString();
        Integer totalCount = cowList.size();
        String rowId = "";
        for (ImportCompanyVO model : cowList) {
            rowId = UUID.randomUUID().toString();
            // ????????????
            if (StringUtils.isBlank(model.getType())) {
                addFailData(model, "????????????????????????", importBatch, "partnerName", rowId);
                cowList.remove(model);
            }
            if (StringUtils.isBlank(model.getName())) {
                addFailData(model, "????????????????????????", importBatch, "name", rowId);
                cowList.remove(model);
            }

            if (StringUtils.isNotBlank(model.getParentId()) && !CheckUtils.isNumeric(model.getParentId())) {
                addFailData(model, "????????????Id???????????????", importBatch, "parentId", rowId);
                cowList.remove(model);
            }

            if (StringUtils.isNotBlank(model.getOpenShop()) && (!CheckUtils.isNumeric(model.getOpenShop()) || Long.valueOf(model.getOpenShop()) < 0 || Long.valueOf(model.getOpenShop()) > 2)) {
                addFailData(model, "??????????????????", importBatch, "openShop", rowId);
                cowList.remove(model);
            }

            insertDb();
        }


        if (cowList.size() > 0) {
            SysUserRedisVO loginUser = securityUtil.getCurrentLoginUser();
            //???????????????
            for (ImportCompanyVO model : cowList) {
                SysOrgCompany sysOrgCompany = new SysOrgCompany();
                BeanUtil.copyProperties(model, sysOrgCompany);
                sysOrgCompany.setCodeType(2);
                sysOrgCompany.setOrgId(loginUser.getUamOrgId());
                SysOrgCompany save = sysOrgCompanyRepository.save(sysOrgCompany);
                String brandsId = model.getBrandsId();
                if (StringUtils.isNotBlank(brandsId)) {
                    String[] split = brandsId.split(",");
                    for (String s : split) {
                        //??????????????????????????????
                        SysOrgCompanyBrands sysOrgCompanyBrands = new SysOrgCompanyBrands();
                        sysOrgCompanyBrands.setOrgId(loginUser.getUamOrgId());
                        sysOrgCompanyBrands.setCompanyId(save.getId());
                        sysOrgCompanyBrands.setBrandsId(Long.valueOf(s));
                        sysOrgCompanyBrandsRepository.save(sysOrgCompanyBrands);
                    }
                }
            }
        }

        // ??????????????????
        // ?????????????????????????????????
        List<ImportCompanyFail> failList = importCompanyFailRepository.findByImportBatch(importBatch);
        JSONObject byByte = null;
        if (CollectionUtil.isNotEmpty(failList)) {

            // ????????????,?????????????????????
            List<String> readList = new ArrayList<>();
            try {
                // List<ImportTemplate> all = importTemplateRepository.findAll();
                //  readList = ExcelUtil.readExcel(all.get(0).getTemplateUrl(),all.get(0).getTemplateName());
                for (String heaeder : map.keySet()) {
                    readList.add(heaeder);
                }
            } catch (Exception e) {
                log.error("??????????????????", e);
                throw new UamException(ResponseEnum.FAILD.getCode(), "??????????????????");
            }
            //   List<String> attentionList = Lists.newLinkedList();
            // title.add(readList.get(0));
            //List<String> titleList = ListUtils.getDiffrent2(readList, attentionList);
            List<String> titleList = new ArrayList<>();
            for (String header : readList) {
                titleList.add(header);
            }
            titleList.add("????????????");

            // ????????????
            try {
                String errorName = String.valueOf(System.currentTimeMillis());
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                WritableWorkbook book = Workbook.createWorkbook(bos);
                WritableSheet sheet = book.createSheet(errorName, 0);

                // ?????????????????????
                sheet.getSettings().setDefaultColumnWidth(20);
                WritableFont font = new WritableFont(WritableFont.ARIAL, 10, WritableFont.NO_BOLD);//????????????,??????,????????????
                WritableCellFormat wcf = new WritableCellFormat(font);
                wcf.setBorder(Border.ALL, BorderLineStyle.THIN);//??????????????????????????????,???????????????????????????????????????
                wcf.setAlignment(Alignment.LEFT);

                WritableFont redFont = new WritableFont(WritableFont.ARIAL, 10, WritableFont.NO_BOLD, false, UnderlineStyle.NO_UNDERLINE, jxl.format.Colour.RED);
                WritableCellFormat redWcf = new WritableCellFormat(redFont);
                redWcf.setBorder(Border.ALL, BorderLineStyle.THIN);
                redWcf.setAlignment(Alignment.LEFT);

                // ????????????????????????????????????
                //sheet.addCell(new Label(0, 0, attentionList.get(0), wcf));
                // ???????????????(?????????????????????????????????????????????????????????????????????)
                //  sheet.mergeCells(0, 0, titleList.size() - 1, 0);
                // ??????????????????????????????
                //  sheet.setRowView(0, 1600);

                // ???????????????????????????
                for (int i = 0; i < titleList.size() - 1; i++) {
                    sheet.addCell(new Label(i, 0, titleList.get(i), wcf));
                }
                // ???????????????????????????????????????????????????,????????????
                sheet.addCell(new Label(titleList.size() - 1, 0, titleList.get(titleList.size() - 1), redWcf));

                // ??????excel?????????????????????????????????,??????????????????,????????????????????????????????????,???for?????????0??????????????????,???????????????2
                int row = 1;

                // ??????3???????????????????????????????????????
                for (int i = 0; i < failList.size(); i++) {
                    sheet.addCell(new Label(0, i + row, failList.get(i).getType(), wcf));
                    sheet.addCell(new Label(1, i + row, failList.get(i).getName(), wcf));
                    sheet.addCell(new Label(2, i + row, failList.get(i).getCompanyId(), wcf));
                    sheet.addCell(new Label(3, i + row, failList.get(i).getContacts(), wcf));
                    sheet.addCell(new Label(4, i + row, failList.get(i).getContactsPhone(), wcf));
                    sheet.addCell(new Label(5, i + row, failList.get(i).getParentId(), wcf));
                    sheet.addCell(new Label(6, i + row, failList.get(i).getCode(), wcf));
                    sheet.addCell(new Label(7, i + row, failList.get(i).getOpenShop(), wcf));
                    sheet.addCell(new Label(8, i + row, failList.get(i).getBrandsId(), wcf));
                    sheet.addCell(new Label(9, i + row, failList.get(i).getErrorMsg(), redWcf));
                }

                book.write();
                book.close();

                byte[] bytes = bos.toByteArray();

                byByte = upLoadUtil.getUpLoadJsonByByte(errorName + ".csv", bytes);

            } catch (Exception ex) {
                log.error("??????", ex);
                throw new UamException(ResponseEnum.FAILD.getCode(), "??????????????????");
            }
        }

        // ?????????????????????
        ImportHistory history = new ImportHistory();

        history.setFileName(file.getOriginalFilename());
        history.setImportBatch(importBatch);
        history.setSuccessNumber(cowList.size());
        history.setFailNumber(totalCount - cowList.size());

        if (null != byByte) {
            Object data = byByte.get("data");
            if (data != null) {
                JSONArray objects = JSONArray.parseArray(JSONObject.toJSONString(data));
                JSONObject jsonObject = JSONObject.parseObject(objects.get(0).toString());
                String ossUrl = jsonObject.getString("ossUrl");
                history.setErrorFileUrl(ossUrl);
            }
        }
        ImportHistory save = importHistoryRepository.save(history);
        log.info("???????????????????????????");
        return save.getId();
    }

    @Override
    public ImportHistoryVO importErrorCompany(Long id) {

        Optional<ImportHistory> byId = importHistoryRepository.findById(id);
        if (byId.isPresent()) {
            ImportHistory importHistory = byId.get();
            ImportHistoryVO importHistoryVO = new ImportHistoryVO();
            BeanUtil.copyProperties(importHistory, importHistoryVO);
            return importHistoryVO;
        }
        return null;
    }

    @Override
    public ImportTemplate importTemplate() {

        List<ImportTemplate> all = importTemplateRepository.findAll();
        if (CollectionUtil.isNotEmpty(all)) {
            return all.get(0);
        }
        return null;
    }

    @Override
    public SysOrgCompanyUpdateVO detailByPk(Long pk) {
        SysOrgCompanyUpdateVO vo = new SysOrgCompanyUpdateVO();
        Optional<SysOrgCompany> byId = sysOrgCompanyRepository.findById(pk);
        if (byId.isPresent()) {
            SysOrgCompany sysOrgCompany = byId.get();
            BeanUtils.copyProperties(sysOrgCompany, vo);
            List<SysOrgCompanyBrands> relationList = sysOrgCompanyBrandsService.findByCompanyId(sysOrgCompany.getId());
            if (CollectionUtil.isNotEmpty(relationList)) {
                SysOrgCompanyBrands companyBrands = relationList.get(0);
                vo.setBrandId(companyBrands.getBrandsId());
                vo.setMiniAppId(companyBrands.getMiniAppId());
                vo.setServiceId(companyBrands.getServiceId());
            }
        }
        return vo;
    }

    @Override
    public BasePage<SysOrgCompany> list(Integer curPage, Integer size, SysOrgCompany sysOrgCompany) {
        PageBounds pageBounds = new PageBounds(curPage, size);
        Page<SysOrgCompany> sysOrgCompanys = sysOrgCompanyRepository.findAll((r, q, c) -> {
            return (new QueryConditionUtil()).where(sysOrgCompany, r, c);
        }, PageRequest.of(pageBounds.getOffset(), pageBounds.getLimit()));
        return new BasePage(sysOrgCompanys);
    }

    @Override
    public List<SysOrgCompanyTreeVO> findAll(Integer status, Long id,Integer openShop) throws MyException {
        SysUserRedisVO loginUser = securityUtil.getCurrentLoginUser();
        List<SysOrgCompanyTreeVO> list = new ArrayList<>();
        List<SysOrgCompany> sysOrgCompany = null;
        if(openShop!=null) {
            sysOrgCompany = sysOrgCompanyRepository.findByOrgIdAndStatusAndOpenShop(loginUser.getUamOrgId(),1,openShop);
        }else  if(status != null) {
            sysOrgCompany = sysOrgCompanyRepository.findByOrgIdAndStatus(loginUser.getUamOrgId(), status);

            //?????????????????????????????????
            sysOrgCompany = sysOrgCompany.stream().filter(item -> item.getId() != id).collect(Collectors.toList());
        } else {
            sysOrgCompany = sysOrgCompanyRepository.findByOrgId(loginUser.getUamOrgId());
        }
        if (!sysOrgCompany.isEmpty()) {
            List<SysOrgCompanyTreeVO> collect = sysOrgCompany.stream().map(s -> {
                SysOrgCompanyTreeVO sysOrderPayDetail = new SysOrgCompanyTreeVO();
                BeanUtil.copyProperties(s, sysOrderPayDetail);
                return sysOrderPayDetail;
            }).collect(Collectors.toList());
            List<SysOrgCompanyTreeVO> tree = this.getTree(collect, 0L);
            return tree;
        }
        return list;
    }


    private List<SysOrgCompanyTreeVO> getTree(List<SysOrgCompanyTreeVO> sysOrgCompanies, Long parentId) throws MyException {
        try {
            List<SysOrgCompanyTreeVO> childTree = getChildTree(sysOrgCompanies, parentId);
            for (SysOrgCompanyTreeVO sysOrgCompany : childTree) {
                //????????????
                sysOrgCompany.setOfflineShopNumber(this.getShopCount(sysOrgCompany.getId()));
                //????????????
                this.getCompanyBrandsName(sysOrgCompany);
                sysOrgCompany.setChildren(getTree(sysOrgCompanies, sysOrgCompany.getId()));
            }
            return childTree;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new MyException(ResponseEnum.FAILD.getCode(), "??????????????????");
        }
    }

    private List<SysOrgCompanyTreeVO> getChildTree(List<SysOrgCompanyTreeVO> list, Long id) {
        List<SysOrgCompanyTreeVO> childTree = new ArrayList<>();
        for (SysOrgCompanyTreeVO dept : list) {
            if (dept.getParentId().equals(id)) {
                childTree.add(dept);
            }
        }
        return childTree;
    }

    /**
     * @param companyTreeVO
     * @return
     */
    private void getCompanyBrandsName(SysOrgCompanyTreeVO companyTreeVO) {
        List<SysOrgCompanyBrands> sysOrgCompanyBrands = sysOrgCompanyBrandsService.findByCompanyId(companyTreeVO.getId());

        for (SysOrgCompanyBrands sysOrgCompanyBrand : sysOrgCompanyBrands) {
            SysOrgBrands sysOrgBrands = sysOrgBrandsService.detailByPk(sysOrgCompanyBrand.getBrandsId());
            if (sysOrgBrands != null) {
                companyTreeVO.setRelatedBrands(sysOrgBrands.getName());
                companyTreeVO.setCrmBrandId(sysOrgBrands.getCrmBrandId());
            }
            companyTreeVO.setUamBrandId(sysOrgCompanyBrand.getBrandsId());
        }
    }

    /**
     * @param companyId
     * @return
     */
    private Integer getShopCount(Long companyId) {
        //todo ????????????????????????
        return 0;
    }


    public void addFailData(ImportCompanyVO model, String msg, String importBatch, String field, String rowId) {
        ImportCompanyFailVO fail = new ImportCompanyFailVO();
        BeanUtils.copyProperties(model, fail);
        fail.setImportBatch(importBatch);
        fail.setErrorMsg(msg);
        fail.setErrorField(field);
        fail.setRowId(rowId);
        list.add(fail);
    }

    public void insertDb() {
        if (list.size() > 0) {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    // ???????????????????????????????????????????????????
                    if (list.get(i).getRowId().equals(list.get(j).getRowId())) {

                        // ?????????????????????????????????????????????
                        StringBuffer sb = new StringBuffer(list.get(i).getErrorMsg());
                        StringBuffer sb2 = new StringBuffer(list.get(i).getErrorField());

                        // ?????????????????????????????????????????????
                        ImportCompanyFailVO fail = list.get(j);
                        String errorMsg = fail.getErrorMsg();
                        String errorField = fail.getErrorField();

                        // ???????????????????????????????????????????????????????????????????????????????????????,????????????
                        sb.append(",").append(errorMsg);
                        sb2.append(",").append(errorField);

                        // ??????
                        list.get(i).setErrorMsg(sb.toString());
                        list.get(i).setErrorField(sb2.toString());

                        // ???????????????
                        list.remove(j);
                        j--;
                    }
                }
            }

            for (ImportCompanyFailVO item : list) {
                ImportCompanyFail fail = new ImportCompanyFail();
                //?????????????????????
                BeanUtil.copyProperties(item, fail);
                importCompanyFailRepository.save(fail);
            }
            // ??????????????????:??????????????????,????????????
            list.clear();
        }


    }

}
