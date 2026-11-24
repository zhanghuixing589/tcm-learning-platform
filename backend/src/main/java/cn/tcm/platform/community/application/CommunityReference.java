package cn.tcm.platform.community.application;
public record CommunityReference(String referenceKey,int displayNumber,String title,String authors,Integer publicationYear,String sourceName,String doi,String url,String locationLabel) {
    public CommunityReference numbered(int n){return new CommunityReference(referenceKey,n,title,authors,publicationYear,sourceName,doi,url,locationLabel);}
}
