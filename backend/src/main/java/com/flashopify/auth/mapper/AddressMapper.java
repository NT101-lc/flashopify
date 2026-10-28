package com.flashopify.auth.mapper;

import com.flashopify.auth.domain.Address;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for addresses; module-private. Every query is scoped by user id. */
@Mapper
public interface AddressMapper {

  List<Address> findAllByUser(@Param("userId") long userId);

  Address findByIdAndUser(@Param("id") long id, @Param("userId") long userId);

  /** Fills address.id from the generated key. */
  int insert(Address address);

  int update(Address address);

  int deleteByIdAndUser(@Param("id") long id, @Param("userId") long userId);

  int clearDefault(@Param("userId") long userId);

  int setDefault(@Param("id") long id, @Param("userId") long userId);

  int countByUser(@Param("userId") long userId);

  Address findNewestByUser(@Param("userId") long userId);

  int deleteAllByUser(@Param("userId") long userId);
}
