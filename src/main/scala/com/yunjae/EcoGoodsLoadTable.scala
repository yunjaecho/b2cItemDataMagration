package com.yunjae

import cats.effect.IO
import doobie.{Transactor, Update}
import cats.implicits._
import doobie.implicits._
import scala.io.Source

object EcoGoodsLoadTable extends  App {
  object DataBase {
   val posamllDb = Transactor.fromDriverManager[IO](
     "com.tmax.tibero.jdbc.TbDriver", "jdbc:tibero:thin:@192.168.100.136:8629:tibero", "POSMALL_STATS", "POSMALL_STATS"
    )
  }


  // 상품번호		상품명	친환경 인증 종류	변경 친환경인증 번호	인증 유효 기간	판매업체
  case class EcoGoodsData(gdNo: Int, ptnNo: Int, echoCertNo: String, ecoType: String, ecoStartDt: String, ecoEndDt: String)

  val dataFile = Source.fromResource("posmall-eco-data.txt").getLines().drop(1)

  //dataFile.foreach(line => println(line + " : " + line.split("\\t").length))

  val ecoDatas = dataFile
    .map {data =>
      val splits = data.split("\\t")
      EcoGoodsData(splits(0).toInt, splits(1).toInt, splits(3), splits(2),
        splits(4).split("~")(0).trim.replaceAll(".", ""),
        splits(4).split("~")(1).trim.replaceAll(".", ""))
    }.toList

  Update[EcoGoodsData]("INSERT INTO POSMALL_CERT VALUES(?, ?, ?, ?, ?, ?)", None)
    .updateMany(ecoDatas)
    .transact(DataBase.posamllDb)
    .unsafeRunSync


}
